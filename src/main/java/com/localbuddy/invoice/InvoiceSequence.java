package com.localbuddy.invoice;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-year invoice counter, incremented under a pessimistic lock. */
@Entity
@Table(name = "invoice_sequences")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceSequence {

    @Id
    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber = 0;
}
