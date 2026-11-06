"""Bounded, non-streaming Typecast synthesis for fictional NPC draft scripts only.

This adapter is not exposed as an application speech endpoint. Never pass a
child's original text, transcript, or composed expression to this provider.
Official reference: https://typecast.ai/docs/api-reference/text-to-speech/text-to-speech
"""
from array import array
import base64
import io
import json
import math
import os
import re
import socket
import ssl
import sys
import urllib.error
import urllib.request
import urllib.parse
import wave

ENDPOINT = 'https://api.typecast.ai/v1/text-to-speech'
MAX_AUDIO_BYTES = 3 * 1024 * 1024
VOICE_ENV = {'jun': 'TYPECAST_JUN_VOICE_ID', 'nara': 'TYPECAST_NARA_VOICE_ID'}

class TypecastError(Exception):
    """Only a fixed public code may leave the adapter; no response body or key."""
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def limits_from_env():
    def bound(name, default, ceiling):
        raw = os.environ.get(name, str(default))
        if not re.fullmatch(r'[1-9][0-9]{0,5}', raw) or int(raw) > ceiling:
            raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
        return int(raw)
    # Typecast documents one credit per character. These are request ceilings,
    # not a claim about provider billing or remaining account credits.
    return bound('TYPECAST_MAX_CHARACTERS', 3000, 20000), bound('TYPECAST_MAX_REQUESTS', 40, 64)

# [해설 D01] Typecast 응답을 그대로 재생하지 않고 PCM 형식/길이/크기/실제 샘플을 검사합니다.
# 필요하면 기존 Unity 재생기가 사용하는 mono PCM16/24kHz로 리샘플링합니다.
def normalize_wave(data):
    """Validate PCM and resample to the existing demo's mono PCM16/24kHz format."""
    if len(data) > MAX_AUDIO_BYTES:
        raise TypecastError('TYPECAST_MEDIA_TOO_LARGE')
    try:
        with wave.open(io.BytesIO(data), 'rb') as wav:
            rate, frames = wav.getframerate(), wav.getnframes()
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getcomptype() != 'NONE' or not 8000 <= rate <= 48000 or not .1 <= frames / rate <= 30:
                raise TypecastError('TYPECAST_INVALID_AUDIO')
            pcm = wav.readframes(frames)
            if len(pcm) != frames * 2 or not any(pcm):
                raise TypecastError('TYPECAST_INVALID_AUDIO')
        samples = array('h'); samples.frombytes(pcm)
        if sys.byteorder != 'little': samples.byteswap()
        if rate != 24000:
            count = max(1, round(frames * 24000 / rate))
            output = array('h')
            for i in range(count):
                position = i * rate / 24000
                left = min(int(position), frames - 1)
                right = min(left + 1, frames - 1)
                fraction = position - left
                output.append(round(samples[left] * (1 - fraction) + samples[right] * fraction))
            samples = output
        if sys.byteorder != 'little': samples.byteswap()
        out = io.BytesIO()
        with wave.open(out, 'wb') as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(24000)
            wav.writeframes(samples.tobytes())
        return out.getvalue()
    except TypecastError:
        raise
    except (wave.Error, EOFError, ValueError, OverflowError):
        raise TypecastError('TYPECAST_INVALID_AUDIO') from None

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward X-API-KEY to a redirect target.
        return None

def _secure_opener():
    import certifi
    return urllib.request.build_opener(_NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=certifi.where())))

def _api_key():
    key = os.environ.get('TYPECAST_API_KEY', '')
    if not key: raise TypecastError('TYPECAST_NOT_CONFIGURED')
    if len(key) > 512 or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
    return key

def _read_response(opener, request, maximum):
    try:
        with opener.open(request, timeout=30) as response:
            if getattr(response, 'status', 200) != 200:
                raise TypecastError('TYPECAST_UNAVAILABLE')
            data = response.read(maximum + 1)
        if len(data) > maximum: raise TypecastError('TYPECAST_MEDIA_TOO_LARGE')
        return data
    except urllib.error.HTTPError as error:
        code = {401:'TYPECAST_AUTH_FAILED',403:'TYPECAST_AUTH_FAILED',402:'TYPECAST_QUOTA_EXCEEDED',
                429:'TYPECAST_RATE_LIMITED',400:'TYPECAST_REQUEST_REJECTED',404:'TYPECAST_REQUEST_REJECTED',422:'TYPECAST_REQUEST_REJECTED'}.get(error.code,'TYPECAST_UNAVAILABLE')
        error.close()
        raise TypecastError(code) from None
    except (TimeoutError, socket.timeout):
        raise TypecastError('TYPECAST_TIMEOUT') from None
    except (urllib.error.URLError, OSError):
        raise TypecastError('TYPECAST_UNAVAILABLE') from None

class TypecastVoiceCatalog:
    """Read-only original voice discovery. No scripts or synthesis are sent."""
    def __init__(self, opener=None):
        self._key = _api_key()
        self.model = os.environ.get('TYPECAST_MODEL', 'ssfm-v30')
        if self.model not in ('ssfm-v30','ssfm-v21'):
            raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
        self._opener = opener or _secure_opener()
        self.requests_attempted = 0
        self.characters_attempted = 0

    def list_voices(self, gender=None, age=None):
        if gender not in (None,'male','female') or age not in (None,'child','teenager','young_adult','middle_age','elder'):
            raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
        query = {'model':self.model,'voice_type':'original'}
        if gender: query['gender'] = gender
        if age: query['age'] = age
        request = urllib.request.Request('https://api.typecast.ai/v3/voices?'+urllib.parse.urlencode(query),
                    headers={'Accept':'application/json','X-API-KEY':self._key}, method='GET')
        self.requests_attempted += 1
        try:
            value = json.loads(_read_response(self._opener, request, 4*1024*1024))
            if not isinstance(value,list) or len(value)>2000:
                raise TypecastError('TYPECAST_INVALID_RESPONSE')
            result = []
            for item in value:
                if not isinstance(item,dict) or not re.fullmatch(r'tc_[A-Za-z0-9_-]{1,100}',item.get('voice_id','')):
                    raise TypecastError('TYPECAST_INVALID_RESPONSE')
                names = item.get('voice_name',{})
                if not isinstance(names,dict): raise TypecastError('TYPECAST_INVALID_RESPONSE')
                models = item.get('models',[])
                if not isinstance(models,list) or not any(isinstance(model,dict) and model.get('version') == self.model for model in models):
                    raise TypecastError('TYPECAST_INVALID_RESPONSE')
                def name(language):
                    text = names.get(language)
                    return ''.join(c for c in text[:100] if c.isprintable()) if isinstance(text,str) else None
                result.append({'voiceId':item['voice_id'],'nameKo':name('kor'),'nameEn':name('eng'),
                    'gender':item.get('gender') if item.get('gender') in ('male','female') else None,
                    'age':item.get('age') if item.get('age') in ('child','teenager','young_adult','middle_age','elder') else None,
                    'model':self.model,'emotionPresets':next(model.get('emotions',[]) for model in models if isinstance(model,dict) and model.get('version')==self.model),
                    'modelSupportsKorean':True,'voiceKoreanSupport':'UNVERIFIED'})
            return result
        except (ValueError, TypeError, UnicodeError):
            raise TypecastError('TYPECAST_INVALID_RESPONSE') from None

class TypecastTts:
    def __init__(self, opener=None):
        self._key = _api_key()
        self._voices = {actor: os.environ.get(name, '') for actor, name in VOICE_ENV.items()}
        if not self._key or not all(self._voices.values()):
            raise TypecastError('TYPECAST_NOT_CONFIGURED')
        if any(not re.fullmatch(r'(?:tc|uc)_[A-Za-z0-9_-]{1,100}', value) for value in self._voices.values()):
            raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
        self.model = os.environ.get('TYPECAST_MODEL', 'ssfm-v30')
        if self.model not in ('ssfm-v30', 'ssfm-v21'):
            raise TypecastError('TYPECAST_CONFIGURATION_INVALID')
        self.max_characters, self.max_requests = limits_from_env()
        self.characters_attempted = 0
        self.requests_attempted = 0
        self._opener = opener or _secure_opener()

    # [해설 D02] 검수된 NPC 대사와 jun/nara 화자만 Typecast에 전달합니다.
    # 기존 호출은 normal/1.0을 유지합니다. 명시 감정 호출은 목소리 지원 정보로 검증한 프리셋을 사용합니다.
    # 이번 견본은 timed=False 경로라 Typecast 문자별 타임스탬프 기능을 사용하지 않습니다.
    def _request(self, text, actor, timed=False, acting_prompt=None):
        if actor not in VOICE_ENV or not isinstance(text, str) or not text.strip() or len(text) > 2000:
            raise TypecastError('TYPECAST_INVALID_NPC_SCRIPT')
        if self.characters_attempted + len(text) > self.max_characters or self.requests_attempted + 1 > self.max_requests:
            raise TypecastError('TYPECAST_BUDGET_EXCEEDED')
        prompt = dict(acting_prompt) if acting_prompt is not None else {'emotion_preset': 'normal', 'emotion_intensity': 1.0}
        if self.model == 'ssfm-v30': prompt['emotion_type'] = 'preset'
        payload = {'voice_id': self._voices[actor], 'text': text, 'model': self.model,
                   'language': 'kor', 'prompt': prompt,
                   'output': {'audio_format': 'wav', 'volume': 100, 'audio_pitch': 0, 'audio_tempo': 1}}
        request = urllib.request.Request(ENDPOINT+('/with-timestamps?granularity=char' if timed else ''), data=json.dumps(payload, ensure_ascii=False).encode(),
                    headers={'Content-Type': 'application/json', 'Accept': 'application/json' if timed else 'audio/wav', 'X-API-KEY': self._key}, method='POST')
        self.characters_attempted += len(text); self.requests_attempted += 1
        # One bounded attempt only. Retrying a charged synthesis is explicit.
        return _read_response(self._opener, request, MAX_AUDIO_BYTES*2 if timed else MAX_AUDIO_BYTES)

    def synthesize_npc(self, text, actor):
        return normalize_wave(self._request(text,actor))

    def synthesize_npc_emotion(self,text,actor,emotion,intensity,capability):
        """기존 음성을 바꾸지 않는 명시 경로. 음원과 실제 요청 연기 정보를 함께 반환합니다."""
        from typecast_emotion import acting
        if actor not in self._voices:raise TypecastError('TYPECAST_INVALID_NPC_SCRIPT')
        performance=acting(emotion,intensity,self._voices[actor],self.model,capability)
        audio=normalize_wave(self._request(text,actor,acting_prompt=performance['prompt']))
        return audio,performance

    def synthesize_npc_timed(self, text, actor):
        """One attempt, same bounded NPC budget, exact script + character alignment.

        Official schema: /v1/text-to-speech/with-timestamps?granularity=char.
        Character timing is display alignment, not A/I/U/E/O phoneme data.
        """
        data=self._request(text,actor,True)
        try:
            result=json.loads(data)
            audio=normalize_wave(base64.b64decode(result['audio'],validate=True))
            with wave.open(io.BytesIO(audio),'rb') as wav:duration=wav.getnframes()/wav.getframerate()
            segments=result['characters']
            if not isinstance(segments,list) or not 1<=len(segments)<=4000:raise ValueError()
            previous=0.;aligned=[]
            for segment in segments:
                start,end=segment['start'],segment['end'];piece=segment['text']
                if type(start) not in (int,float) or type(end) not in (int,float) or not math.isfinite(start) or not math.isfinite(end) or not previous<=start<=end<=duration+.15 or not isinstance(piece,str) or not piece:raise ValueError()
                aligned.append({'text':piece,'start':float(start),'end':float(end)});previous=float(start)
            if ''.join(s['text'] for s in aligned)!=text:raise ValueError()
            return audio,{'format':'EF_TEXT_TIMING_V1','text':text,'durationSeconds':duration,'characters':aligned}
        except (KeyError,TypeError,ValueError,OverflowError,wave.Error):
            raise TypecastError('TYPECAST_INVALID_ALIGNMENT') from None
