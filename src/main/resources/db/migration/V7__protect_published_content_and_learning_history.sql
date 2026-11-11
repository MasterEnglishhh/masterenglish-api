-- Additive upgrade: do not change the checksum of an already applied V6.
CREATE FUNCTION me_history_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER word_reviews_append_only BEFORE UPDATE OR DELETE ON word_reviews
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();

CREATE FUNCTION me_published_revision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Published revision is immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Published revision is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER published_revision_immutable BEFORE UPDATE OR DELETE ON content_revisions
    FOR EACH ROW EXECUTE FUNCTION me_published_revision_immutable();

CREATE FUNCTION me_published_reference_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_parent uuid; new_parent uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_parent := (to_jsonb(OLD)->>TG_ARGV[0])::uuid;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_parent := (to_jsonb(NEW)->>TG_ARGV[0])::uuid;
    END IF;
    IF EXISTS (SELECT 1 FROM content_revisions WHERE id IN (old_parent,new_parent) AND status='PUBLISHED') THEN
        RAISE EXCEPTION 'Published revision references are immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER published_sources_immutable BEFORE INSERT OR UPDATE OR DELETE ON content_source_links
    FOR EACH ROW EXECUTE FUNCTION me_published_reference_immutable('revision_id');
CREATE TRIGGER published_dependencies_immutable BEFORE INSERT OR UPDATE OR DELETE ON revision_dependencies
    FOR EACH ROW EXECUTE FUNCTION me_published_reference_immutable('parent_revision_id');
CREATE TRIGGER published_media_refs_immutable BEFORE INSERT OR UPDATE OR DELETE ON revision_media_refs
    FOR EACH ROW EXECUTE FUNCTION me_published_reference_immutable('revision_id');
