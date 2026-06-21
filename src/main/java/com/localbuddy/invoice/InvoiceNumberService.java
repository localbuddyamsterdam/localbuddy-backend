package com.localbuddy.invoice;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Allocates sequential, gap-free invoice numbers per year under a row lock. */
@Service
public class InvoiceNumberService {

    private final InvoiceSequenceRepository sequenceRepository;

    public InvoiceNumberService(InvoiceSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    @Transactional
    public String next(String prefix, int year) {
        InvoiceSequence sequence = sequenceRepository.findByYearForUpdate(year)
                .orElseGet(() -> {
                    InvoiceSequence created = new InvoiceSequence();
                    created.setYear(year);
                    created.setLastNumber(0);
                    return sequenceRepository.save(created);
                });

        int nextNumber = sequence.getLastNumber() + 1;
        sequence.setLastNumber(nextNumber);
        sequenceRepository.save(sequence);

        return String.format("%s-%d-%04d", prefix, year, nextNumber);
    }
}
