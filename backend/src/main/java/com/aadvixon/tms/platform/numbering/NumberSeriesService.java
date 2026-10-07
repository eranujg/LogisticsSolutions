package com.aadvixon.tms.platform.numbering;

import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues gapless document numbers (GR, challan, invoice, receipt ...).
 *
 * <p>Must be called inside the transaction that saves the document: if that
 * transaction rolls back, the number is returned to the series.
 */
@Service
public class NumberSeriesService {

    private final JdbcClient jdbc;

    NumberSeriesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String documentType, UUID locationId, String financialYear) {
        TenantContext.requireTenantId();
        try {
            return jdbc.sql("SELECT next_document_number(:type, CAST(:location AS uuid), :fy)")
                    .param("type", documentType)
                    .param("location", locationId)
                    .param("fy", financialYear)
                    .query(String.class)
                    .single();
        } catch (DataAccessException e) {
            String cause = e.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains("No active number series")) {
                throw new BusinessRuleException("No active number series for " + documentType
                        + " in financial year " + financialYear + ". Set one up first.");
            }
            throw e;
        }
    }

    /** Financial year label for a date, e.g. "2026-27" (April start) or "2026" (January start). */
    public String financialYear(LocalDate date, int startMonth) {
        return jdbc.sql("SELECT financial_year_label(:date, CAST(:month AS smallint))")
                .param("date", date)
                .param("month", startMonth)
                .query(String.class)
                .single();
    }
}
