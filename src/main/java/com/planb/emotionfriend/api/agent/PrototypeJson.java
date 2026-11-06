package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.learning.CoreDependencyValidator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** RFC8785-compatible restricted P1 domain: safe integers and bounded decimals (including playback speed1.25).
 * Unsupported numeric values are rejected, never silently serialized with Java-specific exponents.
 * Legacy product/Core hashes use their existing codecs and are not rewritten.
 */
public final class PrototypeJson {
    private PrototypeJson() {}
    public static String digest(JsonNode value){return CoreDependencyValidator.sha(canonical(value).getBytes(StandardCharsets.UTF_8));}
    public static String textHash(String text){return CoreDependencyValidator.sha(text.getBytes(StandardCharsets.UTF_8));}
    public static String canonical(JsonNode value) {
        if(value.isObject()) {
            var keys=new ArrayList<String>();value.properties().forEach(e->keys.add(e.getKey()));keys.sort(String::compareTo);
            var out=new StringJoiner(",","{","}");for(String key:keys)out.add(quote(key)+":"+canonical(value.path(key)));return out.toString();
        }
        if(value.isArray()){var out=new StringJoiner(",","[","]");value.forEach(n->out.add(canonical(n)));return out.toString();}
        if(value.isString())return quote(value.asText());
        if(value.isNull())return "null";
        if(value.isBoolean())return value.asBoolean()?"true":"false";
        if(value.isNumber()) {
            BigDecimal n=new BigDecimal(value.asText()).stripTrailingZeros();
            if(n.scale()<=0) {
                if(n.abs().compareTo(new BigDecimal("9007199254740991"))>0)throw new IllegalArgumentException("P1 integer outside safe range");
                return n.toPlainString();
            }
            if(n.abs().compareTo(new BigDecimal("1000"))>0||n.scale()>4)throw new IllegalArgumentException("P1 decimal outside bounded domain");
            return n.toPlainString();
        }
        throw new IllegalArgumentException("Unsupported P1 value");
    }
    private static String quote(String value) {
        var out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");case '\t' -> out.append("\\t");case '\n' -> out.append("\\n");case '\f' -> out.append("\\f");case '\r' -> out.append("\\r");
                default -> {
                    if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("Invalid Unicode");out.append(c).append(value.charAt(++i));}
                    else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid Unicode");else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
