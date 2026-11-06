package com.planb.emotionfriend.api.agent;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;
import com.planb.emotionfriend.api.common.error.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Shape validation only; never calls a model or rewrites generated text. */
@Component
public final class PrototypeContracts {
    private final JsonMapper json=JsonMapper.builder().build();
    private final com.fasterxml.jackson.databind.node.ObjectNode root;
    private final ConcurrentHashMap<String,Schema> schemas=new ConcurrentHashMap<>();
    public PrototypeContracts() {
        try(var in=getClass().getResourceAsStream("/contracts/prototype-p1.schema.json")) {
            root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(in);
        }catch(Exception e){throw new IllegalStateException("Prototype contracts unavailable",e);}
    }
    public void validate(String name,tools.jackson.databind.JsonNode body) {
        var schema=schemas.computeIfAbsent(name,n->{
            if(!root.path("$defs").has(n))throw new IllegalArgumentException("Unknown prototype contract");
            var selected=root.deepCopy();selected.put("$ref","#/$defs/"+n);
            return SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(selected);
        });
        try {
            if(!schema.validate(json.readTree(body.toString()),ctx->ctx.executionConfig(c->c.formatAssertionsEnabled(true))).isEmpty())
                throw new ApiException(ErrorCode.INVALID_SCHEMA);
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(ErrorCode.INVALID_SCHEMA);}
    }
}
