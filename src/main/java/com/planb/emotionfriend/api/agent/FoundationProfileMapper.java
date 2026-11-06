package com.planb.emotionfriend.api.agent;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit mapping. Missing language/relationship data stays null/absent; gender/age never infer ability. */
public final class FoundationProfileMapper {
    private FoundationProfileMapper() {}
    public static JsonNode input(JsonMapper json,UUID source,UUID child,long profileVersion,long authorityRevision,
                                 JsonNode profileView,AgentContracts.Generate request) {
        var root=json.createObjectNode();root.put("contractVersion","EF_ST_AGENT_DRAFT_V1");root.put("jobId",source.toString());
        root.putObject("sourceSnapshot").put("snapshotId",source.toString()).put("inputRevision",1)
            .put("profileVersion",profileVersion).put("authorityRevision",authorityRevision);
        root.put("configurationRef","example-st-authoring-0.1");
        root.putObject("program").put("programDay",request.programDay()).put("localDate",request.localDate().toString())
            .put("timeZone","Asia/Seoul").put("sessionsPerDay",5);
        var p=profileView.path("profile");var target=root.putObject("childProfile");
        target.put("childId",child.toString()).put("nickname",profileView.path("pseudonym").asText());
        target.putArray("ageBands").add(p.path("ageBand").asText());target.put("language",p.path("primaryLanguage").asText());
        target.putNull("caregiverObservedAt");target.putNull("experienceNarrative");target.putNull("emotionExpression");
        target.putObject("understanding").putNull("informationAmount").putNull("causeExplanation").putNull("personReference");
        target.set("priorityGoals",json.valueToTree(request.priorityGoals()));
        for(String field:List.of("interests","preferredActivities","avoidTopics")) {
            var value=p.path(field);if(value.isArray())target.set(field,value);else target.putArray(field);
        }
        var sensory=p.path("sensoryPreferences");
        if(sensory.isArray() && !sensory.isEmpty()) {
            String text=String.join("; ",sensory.valueStream().map(JsonNode::asText).toList());target.put("sensoryPreferences",text.substring(0,Math.min(500,text.length())));
        }
        else target.putNull("sensoryPreferences");
        target.putArray("expressionExamples");target.put("characterPreference",request.characterPreference());
        var events=root.putArray("dailyEvents");int i=0;
        for(var event:request.dailyEvents()) {
            var e=events.addObject();e.put("eventId",source+"-report-"+(++i));e.put("occurredOn",event.occurredOn().toString());
            e.put("source","CAREGIVER_REPORT");e.put("text",event.text());
            if(event.reportedFeeling()==null)e.putNull("reportedFeeling");else e.put("reportedFeeling",event.reportedFeeling());
            e.put("allowedUse","CHILD_REPLY_CANDIDATE_ONLY");
        }
        var continuity=root.putObject("continuity");continuity.put("revision",0);
        // An absent source is not evidence that this is the child's first meeting.
        for(String field:List.of("relationships","knownFacts","openThreads","recentTopics","placePermissions"))continuity.putArray(field);
        root.putNull("nextTraining");return root;
    }
}
