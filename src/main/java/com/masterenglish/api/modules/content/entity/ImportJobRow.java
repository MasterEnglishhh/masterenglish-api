package com.masterenglish.api.modules.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "import_job_rows")
@IdClass(ImportJobRow.ImportJobRowId.class)
public class ImportJobRow {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Id
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "external_key", length = 200)
    private String externalKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "result_content_item_id")
    private UUID resultContentItemId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", columnDefinition = "jsonb")
    private String resultSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", nullable = false, columnDefinition = "jsonb")
    private String errors;

    @Column(name = "processed_at")
    private Instant processedAt;

    public ImportJobRow(
            UUID jobId,
            int rowNumber,
            String inputHash,
            String externalKey,
            String payload,
            String status,
            String errors) {
        this.jobId = jobId;
        this.rowNumber = rowNumber;
        this.inputHash = inputHash;
        this.externalKey = externalKey;
        this.payload = payload;
        this.status = status;
        this.errors = errors != null ? errors : "[]";
    }

    public void markSuccess(UUID resultContentItemId, String resultSnapshot, Instant at) {
        this.status = "SUCCESS";
        this.resultContentItemId = resultContentItemId;
        this.resultSnapshot = resultSnapshot;
        this.processedAt = at;
    }

    public void markFailed(String errors, Instant at) {
        this.status = "FAILED";
        this.errors = errors != null ? errors : "[]";
        this.processedAt = at;
    }

    public static class ImportJobRowId implements Serializable {
        private UUID jobId;
        private int rowNumber;

        public ImportJobRowId() {}

        public ImportJobRowId(UUID jobId, int rowNumber) {
            this.jobId = jobId;
            this.rowNumber = rowNumber;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ImportJobRowId that = (ImportJobRowId) o;
            return rowNumber == that.rowNumber && Objects.equals(jobId, that.jobId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, rowNumber);
        }
    }
}
