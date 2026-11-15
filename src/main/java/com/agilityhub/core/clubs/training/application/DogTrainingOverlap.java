package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogTrainingOverlapPort;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** S09 → S08 (BOOK-02): the dog's ACTIVE training bookings overlapping a class, the same query as R-09-10 step 3. */
@Service
public class DogTrainingOverlap implements DogTrainingOverlapPort {
    private final TrainingBookingRepository bookings;
    public DogTrainingOverlap(TrainingBookingRepository bookings) { this.bookings = bookings; }
    @Override public boolean dogInTraining(String dogId, Instant from, Instant to) { return !bookings.activeForDog(dogId, from, to).isEmpty(); }
}
