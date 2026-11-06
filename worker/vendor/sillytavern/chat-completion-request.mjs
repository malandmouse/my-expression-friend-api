// Derived from SillyTavern 06bde939, AGPL-3.0. See UPSTREAM.json and THIRD_PARTY_NOTICES.md.
// Original request-body and fetch-config blocks retained; UI/logging/secret-store dependencies removed.
// 변경: 호출 설정은 서버가 지정하며 transport를 주입해 유료 호출 없는 검증을 지원한다.
export function buildRequest({model, messages, jsonSchema, maxOutputTokens=24000, reasoningEffort='low'}) {
    const request = {body: {model, messages, json_schema: jsonSchema, max_completion_tokens:maxOutputTokens, stream:false, n:1}};
    const bodyParams = /^(gpt-5|gpt-6|o[1-9])/.test(model) ? {reasoning_effort:reasoningEffort} : {};
    const isTextCompletion = false;
    const textPrompt = '';
        if (request.body.json_schema && !bodyParams['response_format']) {
            bodyParams['response_format'] = {
                type: 'json_schema',
                json_schema: {
                    name: request.body.json_schema.name,
                    strict: request.body.json_schema.strict ?? true,
                    schema: request.body.json_schema.value,
                },
            };
        }

        const requestBody = {
            'messages': isTextCompletion === false ? request.body.messages : undefined,
            'prompt': isTextCompletion === true ? textPrompt : undefined,
            'model': request.body.model,
            'temperature': request.body.temperature,
            'max_tokens': request.body.max_tokens,
            'max_completion_tokens': request.body.max_completion_tokens,
            'stream': request.body.stream,
            'presence_penalty': request.body.presence_penalty,
            'frequency_penalty': request.body.frequency_penalty,
            'top_p': request.body.top_p,
            'top_k': request.body.top_k,
            'stop': isTextCompletion === false ? request.body.stop : undefined,
            'logit_bias': request.body.logit_bias,
            'seed': request.body.seed,
            'n': request.body.n,
            ...bodyParams,
        };

    return requestBody;
}
export async function sendRequest(requestBody, {apiKey, signal, fetchImpl}) {
    if (typeof fetchImpl !== 'function') throw new Error('Explicit transport required');
    const endpointUrl = 'https://api.openai.com/v1/chat/completions';
    const controller = {signal};
    const headers = {};
    const fetch = fetchImpl;
        const config = {
            method: 'post',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + apiKey,
                ...headers,
            },
            body: JSON.stringify(requestBody),
            signal: controller.signal,
        };

        const fetchResponse = await fetch(endpointUrl, config);

    return fetchResponse;
}
