package org.kitout.application.team;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.PersonalConfigRepository;
import org.kitout.application.config.KitoutConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * The settings every part of kitout sees: the developer's {@code config.json} with the team file
 * layered underneath ({@link TeamOverlay}). Saving writes the developer's own changes only
 * ({@link TeamOverlay#unapply}), so team values never end up in config.json.
 */
@ApplicationScoped
public class TeamAwareConfigRepository implements ConfigRepository {

    private final PersonalConfigRepository personal;
    private final TeamDefaults team;

    public TeamAwareConfigRepository(PersonalConfigRepository personal, TeamService team) {
        this.personal = personal;
        this.team = team;
    }

    @Override
    public KitoutConfig load() {
        return overlay(personal.load());
    }

    @Override
    public KitoutConfig update(UnaryOperator<KitoutConfig> change) {
        return overlay(personal.update(before -> {
            KitoutConfig shown = overlay(before);
            return team.current().isPresent() ? TeamOverlay.unapply(before, shown, change.apply(shown)) : change.apply(shown);
        }));
    }

    private KitoutConfig overlay(KitoutConfig c) {
        return team.current().map(t -> TeamOverlay.apply(c, t, LocalDate.now())).orElse(c);
    }
}
