package com.aadvixon.tms.consignment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A consignment as entered on the booking screen. Freight and charges may be
 * left empty to take them from the rate card; values entered below the rate
 * need approval.
 */
record ConsignmentRequest(
        @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9/_-]+") String manualNo,
        @Size(max = 30) String manualBookNo,
        LocalDate cnDate,
        @NotNull UUID bookingLocationId,
        UUID deliveryLocationId,
        @NotNull UUID originCityId,
        @NotNull UUID destinationCityId,
        @NotBlank @Pattern(regexp = "PAID|TO_PAY|TBB|FOC") String paymentType,
        @Pattern(regexp = "DIRECT|TRANSIT|CROSSING|LOCAL") String movementType,
        @Pattern(regexp = "FTL|PTL|EXPRESS|LOCAL|CONTAINER|ODC") String service,
        @Pattern(regexp = "GODOWN|DOOR") String pickupType,
        @Pattern(regexp = "GODOWN|DOOR") String deliveryType,
        @Size(max = 50) String vehicleType,
        LocalDate expectedDeliveryDate,
        @NotNull UUID consignorId,
        @NotNull UUID consigneeId,
        UUID billToId,
        @NotEmpty @Valid List<PackageLine> packages,
        @DecimalMin("0") BigDecimal declaredValue,
        List<@Size(max = 40) String> invoiceNumbers,
        @Pattern(regexp = "[0-9]{12}") String ewayBillNo,
        LocalDate ewayBillValidUntil,
        @Pattern(regexp = "OWNER|CARRIER") String risk,
        @Size(max = 200) String privateMarks,
        @Size(max = 500) String instructions,
        @DecimalMin("0") BigDecimal freight,
        @Valid List<ChargeAmount> charges,
        @DecimalMin("0") BigDecimal discount,
        @Size(max = 300) String overrideReason,
        @Pattern(regexp = "RCM|TRANSPORTER|EXEMPT") String taxPaidBy,
        @DecimalMin("0") @DecimalMax("40") BigDecimal taxRate) {

    record PackageLine(
            @NotNull @Min(1) Integer packages,
            @NotBlank @Size(max = 30) String packageType,
            @NotBlank @Size(max = 200) String saidToContain,
            @Size(max = 12) String hsnCode,
            @DecimalMin("0") BigDecimal actualWeightKg,
            @DecimalMin("0") BigDecimal volumeCft,
            @DecimalMin("0") BigDecimal value) {
    }

    record ChargeAmount(@NotNull UUID chargeHeadId, @NotNull @DecimalMin("0") BigDecimal amount) {
    }
}
