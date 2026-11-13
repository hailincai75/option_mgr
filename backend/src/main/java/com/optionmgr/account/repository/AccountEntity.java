package com.optionmgr.account.repository;

import com.optionmgr.account.domain.AccountNames;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** FEAT-033 account: a name (unique case-insensitively) and an optional broker note; never an account number. */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "name_key", nullable = false, length = 30)
    private String nameKey;

    @Column(name = "broker_note", length = 50)
    private String brokerNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    public AccountEntity(String name, String brokerNote, Instant createdAt) {
        rename(name, brokerNote);
        this.createdAt = createdAt;
    }

    public void rename(String name, String brokerNote) {
        this.name = AccountNames.normalize(name);
        this.nameKey = AccountNames.key(name);
        this.brokerNote = AccountNames.normalizeBrokerNote(brokerNote);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getBrokerNote() {
        return brokerNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
