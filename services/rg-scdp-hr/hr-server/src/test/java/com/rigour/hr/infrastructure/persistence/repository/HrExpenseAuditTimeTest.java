package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.hr.api.v1.model.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A database clock ahead of the application must not reverse the displayed business chronology. */
class HrExpenseAuditTimeTest {
    final HrExpenseWorkflowTest db = new HrExpenseWorkflowTest();
    @BeforeEach void setup() throws Exception {
        db.setup();
        db.jdbc.execute("SET TIME ZONE 'UTC'");
        // Reproduce separate server clocks deterministically without changing either real clock.
        db.jdbc.execute("ALTER TABLE hr_expense ALTER COLUMN created_time SET DEFAULT DATEADD('SECOND',45,CURRENT_TIMESTAMP)");
        db.jdbc.execute("ALTER TABLE hr_expense ALTER COLUMN updated_time SET DEFAULT DATEADD('SECOND',45,CURRENT_TIMESTAMP)");
    }
    @AfterEach void close() { db.close(); }

    @Test void creationEditUploadAndConfirmationShareApplicationUtcChronology() {
        var command=db.command(UUID.randomUUID().toString(),null,"TRAVEL","2026-10","expense",null,"320.00");
        Instant from=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var created=db.save(null,command);
        Instant through=Instant.now();
        assertThat(created.createdTime()).isBetween(from,through);
        assertThat(created.updatedTime()).isEqualTo(created.createdTime());
        assertThat(db.save(null,command).createdTime()).isEqualTo(created.createdTime());
        var edited=db.save(created.id(),db.command(command.requestId(),created.revision(),"TRAVEL","2026-10","expense",null,"330.00"));
        assertThat(edited.createdTime()).isEqualTo(created.createdTime());
        assertThat(edited.updatedTime()).isAfterOrEqualTo(created.createdTime());
        var file=new HrExpenseAttachmentView(UUID.randomUUID().toString(),"实际行程凭证.pdf","application/pdf",100,
                "测试录入人",Instant.now().truncatedTo(ChronoUnit.MICROS));
        db.tx.executeWithoutResult(tx -> db.attachments.add("T1",created.id(),edited.revision(),file,"test/private-trip.pdf"));
        var attached=db.store.expenseDetail("T1",created.id(),"hr:expense:read");
        assertThat(file.createdTime()).isAfterOrEqualTo(attached.createdTime());
        assertThat(attached.updatedTime()).isAfterOrEqualTo(file.createdTime());
        assertThat(attached.createdTime()).isEqualTo(created.createdTime());
        var confirmed=db.confirm(attached);
        assertThat(confirmed.createdTime()).isEqualTo(created.createdTime());
        assertThat(confirmed.updatedTime()).isAfterOrEqualTo(attached.updatedTime());
    }
}
