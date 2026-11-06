package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import com.planb.emotionfriend.api.common.error.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** SQL-double counts BLOB reads; validates revocation independently from immutable byte reuse. */
class CoreRuntimeReadCostTest {
    final UUID asset=UUID.randomUUID(),material=UUID.randomUUID(),validation=UUID.randomUUID();
    final JsonMapper json=new JsonMapper();final String hash="a".repeat(64);final byte[] bytes={1,2,3};
    class DB extends JdbcTemplate {
        int blobReads,metadataReads;boolean revoked;String body;
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
            if(sql.startsWith("select * from learning.core_artifact")){blobReads++;return List.of(Map.of("kind","MEDIA","source_id","synthetic","bytes",bytes,"bytes_hash",CoreDependencyValidator.sha(bytes)));}
            if(sql.startsWith("select a.id,a.kind")){metadataReads++;var row=new HashMap<String,Object>();row.put("id",asset);row.put("kind","MEDIA");row.put("source_id","synthetic");row.put("bytes_hash",CoreDependencyValidator.sha(bytes));row.put("revoked",revoked?asset:null);return List.of(row);}
            if(sql.startsWith("select payload_hash"))return List.of(Map.of("payload_hash",hash));
            if(sql.startsWith("select kind,bytes_hash"))return List.of(Map.of("kind","MEDIA","bytes_hash",CoreDependencyValidator.sha(bytes)));
            if(sql.startsWith("select * from learning.core_manuscript"))return List.of(Map.of("payload","{}","payload_hash",hash));
            if(sql.startsWith("select p.body::text"))return List.of(Map.of("body",body));
            throw new AssertionError("Unexpected SQL "+sql);
        }
        @Override public <X>X queryForObject(String sql,Class<X> type,Object...args){if(sql.contains("core_revocation"))return type.cast(revoked);throw new AssertionError(sql);}
    }
    class Validator extends CoreDependencyValidator {
        int validations;
        @Override public Result validate(String payload,List<Artifact> artifacts,Map<String,String> cues){validations++;return new Result("{}",(String)dependency(CoreDependencyValidator.VERSION).get("manifest_hash"),List.of());}
    }
    DB db=new DB();Validator validator=new Validator();CorePublicationService content=new CorePublicationService(db,json,null,null,null,validator,null,Clock.systemUTC(),false,null);
    Map<String,Object> dependency(String version){
        var node=json.createObjectNode().put("payloadHash",hash).put("prototypeBindingHash",hash);
        node.putArray("artifacts").addObject().put("id",asset.toString()).put("kind","MEDIA")
                .put("sourceId","synthetic").put("bytesHash",CoreDependencyValidator.sha(bytes));
        String manifest=node.toString();
        String manifestHash=version.equals(CoreDependencyValidator.VERSION)?LearningCatalog.hash(manifest):hash;
        return Map.of("id",validation,"material_id",material,"payload_hash",hash,"manifest_hash",manifestHash,"validator_version",version,"manifest",manifest,"paths","[]");
    }
    void check(String name,Map<String,Object> dependency) throws Throwable {
        var method=CorePublicationService.class.getDeclaredMethod(name,Map.class);method.setAccessible(true);
        try{method.invoke(content,dependency);}catch(InvocationTargetException e){throw e.getCause();}
    }
    void begin(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();}
    void commit(){var syncs=TransactionSynchronizationManager.getSynchronizations();syncs.forEach(TransactionSynchronization::afterCommit);syncs.forEach(s->s.afterCompletion(0));TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    @AfterEach void clean(){if(TransactionSynchronizationManager.isSynchronizationActive()){TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}}
    @Test void publicationStillValidatesFullBytesWithWarmRuntimeEvidence() throws Throwable {
        var dependency=dependency(CoreDependencyValidator.VERSION);
        begin();check("requireSavedDependencies",dependency);commit();
        begin();check("revalidate",dependency);check("revalidate",dependency);commit();
        assertThat(db.blobReads).isEqualTo(2);assertThat(validator.validations).isEqualTo(2);
    }
    @Test void coldRuntimeUsesImmutablePublicationEvidenceWithoutWaveReadsForBothFormats() throws Throwable {
        for(String version:List.of(CoreDependencyValidator.VERSION,PrototypeBinding.VERSION)){
            var d=dependency(version);
            begin();check("requireSavedDependencies",d);check("requireSavedDependencies",d);commit();assertThat(db.blobReads).isZero();
            db.revoked=true;begin();assertThatThrownBy(()->check("requireSavedDependencies",d)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CONTENT_REVOKED));clean();db.revoked=false;
        }
        assertThat(db.metadataReads).isEqualTo(2);assertThat(validator.validations).isZero();
    }
    @Test void leaseChecksOnlyTheBoundArtifactMetadataAndStillRejectHashOrRevocation(){
        content.requireMediaArtifact(asset,CoreDependencyValidator.sha(bytes));assertThat(db.blobReads).isZero();
        assertThatThrownBy(()->content.requireMediaArtifact(asset,hash)).isInstanceOf(ApiException.class);db.revoked=true;
        assertThatThrownBy(()->content.requireMediaArtifact(asset,CoreDependencyValidator.sha(bytes))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.CONTENT_REVOKED));
    }
    @Test void combinedPlaybackMustMatchTheExactSavedSentence(){
        String digest=CoreDependencyValidator.sha(bytes);db.body="{\"presentation\":{\"nodes\":[{\"sceneId\":\"s1\",\"sentences\":[{\"voiceAssetId\":\""+asset+"\",\"audioHash\":\""+digest+"\"}]}]}}";
        content.requirePresentationCursor(material,1,"s1",0,new RuntimeContracts.LeaseRequest(1,asset,digest));
        assertThatThrownBy(()->content.requirePresentationCursor(material,1,"s1",1,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->content.requirePresentationCursor(material,1,"s1",0,new RuntimeContracts.LeaseRequest(1,UUID.randomUUID(),digest))).isInstanceOf(ApiException.class);
    }
}
