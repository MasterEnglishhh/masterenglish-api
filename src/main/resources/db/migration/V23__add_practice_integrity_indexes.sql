-- V20: Add partial unique indexes enforcing structural integrity for question groups and practice sets.
-- These constraints cannot go in V19 (which created the tables) because they are additive
-- and need a clean migration version for rollback identification.

-- Enforce unique position within a group for active group members (in_current_group = TRUE)
CREATE UNIQUE INDEX uq_group_current_position
    ON questions (group_id, group_position)
    WHERE group_id IS NOT NULL AND in_current_group = TRUE;

-- Enforce unique blank_key within a group for active group members that have one
CREATE UNIQUE INDEX uq_group_current_blank
    ON questions (group_id, blank_key)
    WHERE group_id IS NOT NULL AND in_current_group = TRUE AND blank_key IS NOT NULL;

-- Enforce unique question_id per practice set (a question can appear only once)
CREATE UNIQUE INDEX uq_set_question
    ON set_items (set_id, question_id)
    WHERE question_id IS NOT NULL;

-- Enforce unique group_id per practice set (a group can appear only once)
CREATE UNIQUE INDEX uq_set_group
    ON set_items (set_id, group_id)
    WHERE group_id IS NOT NULL;
