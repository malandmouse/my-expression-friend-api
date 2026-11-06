"""Deterministic NPC semantic-emotion → supported Typecast preset projection.

This is an authored mapping, not an emotion classifier or validated clinical
model. A voice catalog snapshot is required; actual voice quality needs listening.
Official schema checked 2026-11-02:
https://typecast.ai/docs/api-reference/text-to-speech/text-to-speech
https://typecast.ai/docs/api-reference/voices/list-voices
"""
import math
from providers.typecast_tts import TypecastError

VERSION='EF_NPC_TYPECAST_ACTING_V2'
MODEL_PRESETS={
    'ssfm-v30':{'normal','happy','sad','angry','whisper','toneup','tonedown'},
    'ssfm-v21':{'normal','happy','sad','angry'},
}
SEMANTIC_PRESET={
    'NEUTRAL':'normal', 'HAPPY':'happy', 'GRATITUDE':'happy', 'LOVE':'happy',
    'SATISFACTION':'happy', 'RELIEF':'normal', 'SAD':'sad', 'DISAPPOINTMENT':'sad',
    'DISTRESS':'sad', 'SYMPATHY':'tonedown', 'ANGRY':'angry', 'UNFAIRNESS':'angry',
    'SURPRISE':'toneup', 'SHOCK':'toneup', 'FEAR':'tonedown', 'DISGUST':'tonedown',
}


def acting(emotion,intensity,voice_id,model,capability):
    """지원하지 않는 프리셋은 명시적으로 normal에 대응시키고 이유를 반환합니다."""
    if emotion not in SEMANTIC_PRESET or type(intensity) not in (int,float) or not math.isfinite(intensity) or not 0<=intensity<=1:
        raise TypecastError('TYPECAST_EMOTION_INVALID')
    if model not in MODEL_PRESETS or not isinstance(capability,dict) or capability.get('voiceId')!=voice_id or capability.get('model')!=model:
        raise TypecastError('TYPECAST_VOICE_CAPABILITY_REQUIRED')
    presets=capability.get('emotionPresets')
    if not isinstance(presets,list) or not presets or any(not isinstance(x,str) for x in presets) or not set(presets)<=MODEL_PRESETS[model] or 'normal' not in presets:
        raise TypecastError('TYPECAST_VOICE_CAPABILITY_REQUIRED')
    requested=SEMANTIC_PRESET[emotion]
    selected=requested if requested in presets else 'normal'
    # 의미 감정 강도 0..1을 공급자 명세 0..2로 투영합니다. 청취 조정 전의 초기 규칙입니다.
    strength=0.0 if emotion=='NEUTRAL' or selected!=requested else round(2*intensity,4)
    # Mild everyday disappointment/surprise should not sound like severe sadness/shock.
    caps={'DISAPPOINTMENT':0.7,'DISTRESS':1.0,'UNFAIRNESS':0.8,'SYMPATHY':0.7,'FEAR':0.8,'SURPRISE':0.9,'SHOCK':1.2,'GRATITUDE':1.0,'LOVE':0.9}
    strength=min(strength,caps.get(emotion,1.3))
    prompt=dict(emotion_preset=selected,emotion_intensity=strength)
    if model=='ssfm-v30':prompt['emotion_type']='preset'
    return dict(mappingVersion=VERSION,semanticEmotion=emotion,semanticIntensity=intensity,voiceId=voice_id,model=model,
                requestedPreset=requested,selectedPreset=selected,fallbackReason=None if selected==requested else 'VOICE_PRESET_UNSUPPORTED',prompt=prompt)
