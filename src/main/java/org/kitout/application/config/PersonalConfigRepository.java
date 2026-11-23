package org.kitout.application.config;

/**
 * The developer's own {@code config.json}, without the team file layered on top. Only the team
 * code reads it directly; everything else uses {@link ConfigRepository}, which shows the effective
 * settings (team values where the developer has not chosen otherwise).
 */
public interface PersonalConfigRepository extends ConfigRepository {
}
