package com.aadvixon.tms.company;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tax registrations (GSTIN, PAN, BN, EIN, ABN ...) shared by companies, locations and parties. */
@Service
public class TaxRegistrationService {

    public record TaxRegistration(UUID id, String taxType, String number, String stateCode,
                                  LocalDate validFrom, LocalDate validTo, boolean verified) {
    }

    public record TaxRegistrationInput(
            @NotBlank @Pattern(regexp = "[A-Z_]{2,20}") String taxType,
            @NotBlank @Size(max = 30) String number,
            String stateCode,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    private final JdbcClient jdbc;

    TaxRegistrationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaxRegistration> list(String ownerType, UUID ownerId) {
        return jdbc.sql("SELECT * FROM tax_registration WHERE owner_type = :type AND owner_id = :id ORDER BY tax_type")
                .param("type", ownerType)
                .param("id", ownerId)
                .query((rs, n) -> new TaxRegistration(Rows.uuid(rs, "id"), rs.getString("tax_type"),
                        rs.getString("number"), rs.getString("state_code"), Rows.date(rs, "valid_from"),
                        Rows.date(rs, "valid_to"), rs.getBoolean("verified")))
                .list();
    }

    /** Replaces the owner's registrations with the given list. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(String ownerType, UUID ownerId, List<TaxRegistrationInput> registrations) {
        if (registrations == null) {
            return;
        }
        UUID tenantId = TenantContext.requireTenantId();
        jdbc.sql("DELETE FROM tax_registration WHERE owner_type = :type AND owner_id = :id")
                .param("type", ownerType)
                .param("id", ownerId)
                .update();
        for (TaxRegistrationInput r : registrations) {
            jdbc.sql("""
                            INSERT INTO tax_registration (tenant_id, owner_type, owner_id, tax_type, number, state_code, valid_from, valid_to)
                            VALUES (:tenant, :type, :owner, :taxType, :number, :state, :from, :to)
                            """)
                    .param("tenant", tenantId)
                    .param("type", ownerType)
                    .param("owner", ownerId)
                    .param("taxType", r.taxType())
                    .param("number", r.number().trim().toUpperCase())
                    .param("state", r.stateCode())
                    .param("from", r.validFrom())
                    .param("to", r.validTo())
                    .update();
        }
    }
}
