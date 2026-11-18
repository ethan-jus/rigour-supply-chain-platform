package com.rigour.merchant.infrastructure.persistence.repository;
import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitVerificationModels.*;
import com.rigour.shared.core.exception.StateConflictException;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
class JdbcStoreVisitVerificationStoreTest {
    JdbcStoreVisitFieldworkStoreTest f;JdbcStoreVisitVerificationStore verification;JdbcStoreVisitStore visits;
    @BeforeAll static void setup(){JdbcStorePoolStoreTest.setup();}
    @AfterAll static void stop(){JdbcStorePoolStoreTest.stopContainer();}
    @BeforeEach void init(){f=new JdbcStoreVisitFieldworkStoreTest();f.init();verification=new JdbcStoreVisitVerificationStore(f.f.jdbc);visits=new JdbcStoreVisitStore(f.f.jdbc,f.f.store,new JdbcStoreLifecycleStore(f.f.jdbc,f.f.store));f.f.jdbc.update("UPDATE crm_visit SET submitted_at=UTC_TIMESTAMP(6),checked_out_at=UTC_TIMESTAMP(6),status_code='INVALID',verification_state='FAILED',review_reason='DWELL_TOO_SHORT' WHERE tenant_id=? AND id=?",f.f.tenant,f.visit.id());}
    private Verification state(){return verification.read(f.f.hangzhou("crm:store:history-read","2"),f.store,f.visit.id());}
    @Test void requiresOwnAppealBeforeReviewAndRetainsAuditAfterDecision(){
        var owner=f.f.hangzhou("crm:store:visit-write","2");var reviewer=f.f.hangzhou("crm:store:visit-review","2");
        assertThatThrownBy(()->f.f.inTx(()->visits.review(reviewer,f.store,f.visit.id(),new ReviewVisit(state().revision(),"EFFECTIVE","现场记录确认"),Instant.now()))).isInstanceOf(StateConflictException.class);
        var command=new AppealCommand(state().revision(),UUID.randomUUID().toString(),"现场接待时长已符合要求，请核对录音");
        var applied=f.f.inTx(()->verification.appeal(owner,f.store,f.visit.id(),command,Instant.now()));
        assertThat(applied.state()).isEqualTo("APPEALED");assertThat(applied.canAppeal()).isFalse();
        assertThat(applied.appeals()).hasSize(1);assertThat(applied.appeals().getFirst().originalState()).isEqualTo("FAILED");
        assertThat(f.f.inTx(()->verification.appeal(owner,f.store,f.visit.id(),command,Instant.now())).appeals()).hasSize(1);
        assertThat(verification.queue(reviewer,"PENDING",1,20).total()).isEqualTo(1);
        f.f.inTx(()->visits.review(reviewer,f.store,f.visit.id(),new ReviewVisit(applied.revision(),"EFFECTIVE","核对原始录音确认"),Instant.now()));
        assertThat(state().state()).isEqualTo("PASSED");assertThat(state().appeals().getFirst().status()).isEqualTo("RESOLVED");
        assertThat(verification.queue(reviewer,"PENDING",1,20).total()).isZero();
        assertThat(verification.queue(reviewer,"RESOLVED",1,20).total()).isEqualTo(1);
    }
    @Test void rejectsAnotherSalespersonAndRollsBackAnUncommittedAppeal(){
        var command=new AppealCommand(state().revision(),UUID.randomUUID().toString(),"请核对录音");
        assertThatThrownBy(()->f.f.inTx(()->verification.appeal(f.f.hangzhou("crm:store:visit-write","3"),f.store,f.visit.id(),command,Instant.now()))).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->f.f.inTx(()->{verification.appeal(f.f.hangzhou("crm:store:visit-write","2"),f.store,f.visit.id(),command,Instant.now());throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(state().appeals()).isEmpty();assertThat(state().canAppeal()).isTrue();
    }
}
