-- PubTrack schema (MySQL 8.0+). Safe to run repeatedly: every table uses IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS journals (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    subject_area  VARCHAR(80)  NOT NULL,
    review_days   INT          NOT NULL DEFAULT 21,
    CONSTRAINT uq_journal_name UNIQUE (name),
    CONSTRAINT chk_review_days CHECK (review_days BETWEEN 7 AND 90)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS users (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    full_name  VARCHAR(100) NOT NULL,
    email      VARCHAR(150) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT chk_user_role CHECK (role IN ('AUTHOR', 'REVIEWER', 'EDITOR'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS manuscripts (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    journal_id     INT          NOT NULL,
    author_id      INT          NOT NULL,
    title          VARCHAR(300) NOT NULL,
    abstract_text  TEXT         NOT NULL,
    keywords       VARCHAR(300) NOT NULL DEFAULT '',
    status         VARCHAR(30)  NOT NULL,
    current_round  INT          NOT NULL DEFAULT 1,
    submitted_at   DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    decided_at     DATETIME     NULL,
    CONSTRAINT fk_ms_journal FOREIGN KEY (journal_id) REFERENCES journals (id),
    CONSTRAINT fk_ms_author  FOREIGN KEY (author_id)  REFERENCES users (id),
    CONSTRAINT chk_ms_status CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'REVISION_REQUESTED',
                                               'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    KEY idx_ms_status (status),
    KEY idx_ms_journal_status (journal_id, status),
    KEY idx_ms_author (author_id),
    FULLTEXT KEY ft_ms_text (title, keywords, abstract_text)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS review_assignments (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    manuscript_id   INT         NOT NULL,
    reviewer_id     INT         NOT NULL,
    round           INT         NOT NULL,
    assigned_at     DATETIME    NOT NULL,
    due_at          DATETIME    NOT NULL,
    completed_at    DATETIME    NULL,
    recommendation  VARCHAR(20) NULL,
    comments        TEXT        NULL,
    reminder_sent   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_ra_manuscript FOREIGN KEY (manuscript_id) REFERENCES manuscripts (id) ON DELETE CASCADE,
    CONSTRAINT fk_ra_reviewer   FOREIGN KEY (reviewer_id)   REFERENCES users (id),
    CONSTRAINT uq_ra_round UNIQUE (manuscript_id, reviewer_id, round),
    CONSTRAINT chk_ra_recommendation CHECK (recommendation IS NULL OR recommendation IN
                                            ('ACCEPT', 'MINOR_REVISION', 'MAJOR_REVISION', 'REJECT')),
    KEY idx_ra_reviewer_pending (reviewer_id, completed_at),
    KEY idx_ra_due (completed_at, due_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS status_history (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    manuscript_id  INT          NOT NULL,
    from_status    VARCHAR(30)  NULL,
    to_status      VARCHAR(30)  NOT NULL,
    changed_by     INT          NOT NULL,
    changed_at     DATETIME     NOT NULL,
    note           VARCHAR(500) NULL,
    CONSTRAINT fk_sh_manuscript FOREIGN KEY (manuscript_id) REFERENCES manuscripts (id) ON DELETE CASCADE,
    CONSTRAINT fk_sh_user       FOREIGN KEY (changed_by)    REFERENCES users (id),
    KEY idx_sh_manuscript (manuscript_id, changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
