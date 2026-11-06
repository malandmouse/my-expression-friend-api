package com.planb.emotionfriend.api.agent;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;
import org.springframework.stereotype.Component;

/** Wire shape only: no LLM QA, automatic rewriting, approval or publication. */
@Component
public final class AgentEnvelopeValidator {
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Schema schema;
    private final Schema v2;
    public AgentEnvelopeValidator(){
        try(var stream=getClass().getResourceAsStream("/contracts/agent-integration-1.schema.json")){
            schema=SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(json.readTree(stream));
        }catch(Exception e){throw new IllegalStateException("Agent integration schema unavailable",e);}
        try(var stream=getClass().getResourceAsStream("/contracts/agent-integration-2.schema.json")){
            v2=SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(json.readTree(stream));
        }catch(Exception e){throw new IllegalStateException("Agent V2 integration schema unavailable",e);}
    }
    public void validate(String payload){
        try{var body=json.readTree(payload);var selected=body.path("contractVersion").asText().equals("EF_FOUNDATION_AGENT_2")?v2:schema;if(!selected.validate(body,ctx->ctx.executionConfig(c->c.formatAssertionsEnabled(true))).isEmpty())throw new IllegalArgumentException("Invalid agent result");}
        catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Invalid agent result");}
    }
}
