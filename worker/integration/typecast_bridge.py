"""Reuse the existing Typecast adapter. One JSON stdin command, bounded JSON stdout, no key logging."""
import base64
import json
import sys
from providers.typecast_tts import TypecastTts, TypecastVoiceCatalog, TypecastError

def main():
    data=json.loads(sys.stdin.read(20000))
    if data.get('operation')=='voices':
        print(json.dumps({'voices':TypecastVoiceCatalog().list_voices()},ensure_ascii=False))
        return
    tts=TypecastTts()
    audio,acting=tts.synthesize_npc_emotion(data['text'],data['actorId'],data['emotion'],data['intensity'],data['capability'])
    print(json.dumps({'audioBase64':base64.b64encode(audio).decode('ascii'),'acting':acting}))

if __name__=='__main__':
    try:main()
    except TypecastError as error:
        print(json.dumps({'errorCode':error.code}));sys.exit(2)
    except Exception:
        print('{"errorCode":"TYPECAST_BRIDGE_FAILED"}');sys.exit(2)
