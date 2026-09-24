package com.agilityhub.core.clubs.census.persistence;

import java.lang.annotation.*;

/**
 * A census field owned by another vertical (S08 `Member.lastDogForClass`, S09 `Member.lastDogForTraining`) and written
 * only with {@link CensusRepository#setField}: a census save never writes it, and it takes no part in the entity's
 * optimistic lock, so a booking never makes an open census form stale (E5-T08).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ForeignOwned { }
