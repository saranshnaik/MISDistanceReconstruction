package com.moveinsync.distance.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * The money side of a trip. billedKm is frozen when the invoice closes, finalBilledKm is
 * billedKm plus the adjustments in order. Both are null while unbilled.
 */
public record BillingSummary(BigDecimal billedKm, BigDecimal finalBilledKm, List<AdjustmentView> adjustments) {
}
