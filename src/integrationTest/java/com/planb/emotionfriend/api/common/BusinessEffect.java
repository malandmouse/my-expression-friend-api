package com.planb.emotionfriend.api.common;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "business_effect", schema = "test_support")
class BusinessEffect {
    @Id private UUID id;
    private int amount;

    protected BusinessEffect() {}
    BusinessEffect(UUID id, int amount) { this.id = id; this.amount = amount; }
}
