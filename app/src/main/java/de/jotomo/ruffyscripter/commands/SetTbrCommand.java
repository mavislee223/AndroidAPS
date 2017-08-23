package de.jotomo.ruffyscripter.commands;

import android.os.SystemClock;

import org.monkey.d.ruffy.ruffy.driver.display.MenuAttribute;
import org.monkey.d.ruffy.ruffy.driver.display.MenuType;
import org.monkey.d.ruffy.ruffy.driver.display.menu.MenuTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.jotomo.ruffyscripter.RuffyScripter;

import static org.monkey.d.ruffy.ruffy.driver.display.MenuType.MAIN_MENU;
import static org.monkey.d.ruffy.ruffy.driver.display.MenuType.TBR_DURATION;
import static org.monkey.d.ruffy.ruffy.driver.display.MenuType.TBR_MENU;
import static org.monkey.d.ruffy.ruffy.driver.display.MenuType.TBR_SET;
import static org.monkey.d.ruffy.ruffy.driver.display.MenuType.WARNING_OR_ERROR;

public class SetTbrCommand extends BaseCommand {
    private static final Logger log = LoggerFactory.getLogger(SetTbrCommand.class);

    private final long percentage;
    private final long duration;

    public SetTbrCommand(long percentage, long duration) {
        this.percentage = percentage;
        this.duration = duration;
    }

    @Override
    public List<String> validateArguments() {
        List<String> violations = new ArrayList<>();

        if (percentage % 10 != 0) {
            violations.add("TBR percentage must be set in 10% steps");
        }
        if (percentage < 0 || percentage > 500) {
            violations.add("TBR percentage must be within 0-500%");
        }

        if (percentage != 100) {
            if (duration % 15 != 0) {
                violations.add("TBR duration can only be set in 15 minute steps");
            }
            if (duration > 60 * 24) {
                violations.add("Maximum TBR duration is 24 hours");
            }
        }

        if (percentage == 0 && duration > 120) {
            violations.add("Max allowed zero-temp duration is 2h");
        }

        return violations;
    }

    @Override
    public CommandResult execute() {
        try {
            log.debug("1. going from " + scripter.getCurrentMenu() + " to TBR_MENU");
            int retries = 5;
            while (!scripter.goToMainTypeScreen(TBR_MENU, 3000)) {
                retries--;
                if (retries == 0)
                    throw new CommandException().message("not able to find TBR_MENU: stuck in " + scripter.getCurrentMenu());
                SystemClock.sleep(500);
                if (scripter.getCurrentMenu().getType() == TBR_MENU)
                    break;
            }

            if (scripter.getCurrentMenu().getType() != TBR_MENU)
                throw new CommandException().message("not able to find TBR_MENU: stuck in " + scripter.getCurrentMenu());

            log.debug("2. entering " + scripter.getCurrentMenu());
            retries = 5;
            while (!scripter.enterMenu(TBR_MENU, MenuType.TBR_SET, RuffyScripter.Key.CHECK, 2000)) {
                retries--;
                if (retries == 0)
                    throw new CommandException().message("not able to find TBR_SET: stuck in " + scripter.getCurrentMenu());
                SystemClock.sleep(500);
                if (scripter.getCurrentMenu().getType() == TBR_SET)
                    break;
                if (scripter.getCurrentMenu().getType() == TBR_DURATION) {
                    scripter.pressMenuKey();
                    scripter.waitForScreenUpdate(1000);
                }
            }

            log.debug("SetTbrCommand: 3. getting/setting basal percentage in " + scripter.getCurrentMenu());
            retries = 30;

            double currentPercentage = scripter.readBlinkingValue(Double.class, MenuAttribute.BASAL_RATE);
            while (currentPercentage != percentage && retries >= 0) {
                retries--;
                currentPercentage = scripter.readBlinkingValue(Double.class, MenuAttribute.BASAL_RATE);
                if (currentPercentage != percentage) {
                    int requestedPercentage = (int) percentage;
                    int actualPercentage = (int) currentPercentage;
                    int steps = (requestedPercentage - actualPercentage) / 10;
                    log.debug("Adjusting basal(" + requestedPercentage + "/" + actualPercentage + ") with " + steps + " steps and " + retries + " retries left");
                    scripter.step(steps, (steps < 0 ? RuffyScripter.Key.DOWN : RuffyScripter.Key.UP), 500);
                    scripter.waitForScreenUpdate(1000);
                }
                scripter.waitForScreenUpdate(1000);
            }
            if (currentPercentage < 0 || retries < 0)
                throw new CommandException().message("unable to set basal percentage");

            log.debug("4. checking basal percentage in " + scripter.getCurrentMenu());
            scripter.waitForScreenUpdate(1000);
            currentPercentage = scripter.readBlinkingValue(Double.class, MenuAttribute.BASAL_RATE);
            if (currentPercentage != percentage)
                throw new CommandException().message("Unable to set percentage. Requested: " + percentage + ", value displayed on pump: " + currentPercentage);

            if (currentPercentage != 100) {
                log.debug("5. change to TBR_DURATION from " + scripter.getCurrentMenu());
                retries = 5;
                while (retries >= 0 && !scripter.enterMenu(TBR_SET, MenuType.TBR_DURATION, RuffyScripter.Key.MENU, 2000)) {
                    retries--;
                    if (retries == 0)
                        throw new CommandException().message("not able to find TBR_SET: stuck in " + scripter.getCurrentMenu());
                    SystemClock.sleep(500);
                    if (scripter.getCurrentMenu().getType() == TBR_DURATION)
                        break;
                    if (scripter.getCurrentMenu().getType() == TBR_SET) {
                        scripter.pressMenuKey();
                        scripter.waitForScreenUpdate(1000);
                    }
                }

                log.debug("6. getting/setting duration in " + scripter.getCurrentMenu());
                retries = 30;

                double currentDuration = scripter.readDisplayedDuration();
                while (currentDuration != duration && retries >= 0) {
                    retries--;
                    currentDuration = scripter.readDisplayedDuration();
                    log.debug("Requested time: " + duration + " actual time: " + currentDuration);
                    if (currentDuration != duration) {
                        int requestedDuration = (int) duration;
                        int actualDuration = (int) currentDuration;
                        int steps = (requestedDuration - actualDuration) / 15;
                        if (currentDuration + (steps * 15) < requestedDuration)
                            steps++;
                        else if (currentDuration + (steps * 15) > requestedDuration)
                            steps--;
                        log.debug("Adjusting duration(" + requestedDuration + "/" + actualDuration + ") with " + steps + " steps and " + retries + " retries left");
                        scripter.step(steps, (steps > 0 ? RuffyScripter.Key.UP : RuffyScripter.Key.DOWN), 500);
                        scripter.waitForScreenUpdate(1000);
                    }
                }
                if (currentDuration < 0 || retries < 0)
                    throw new CommandException().message("unable to set duration, requested:" + duration + ", displayed on pump: " + currentDuration);

                log.debug("7. checking duration in " + scripter.getCurrentMenu());
                scripter.waitForScreenUpdate(1000);
                currentDuration = scripter.readDisplayedDuration();
                if (currentDuration != duration)
                    throw new CommandException().message("wrong duration! Requested: " + duration + ", displayed on pump: " + currentDuration);
            }

            log.debug("8. confirming TBR om " + scripter.getCurrentMenu());
            retries = 5;
            while (retries >= 0 && (scripter.getCurrentMenu().getType() == TBR_DURATION || scripter.getCurrentMenu().getType() == TBR_SET)) {
                retries--;
                scripter.pressCheckKey();
                scripter.waitForScreenUpdate(1000);
            }
            if (retries < 0 || scripter.getCurrentMenu().getType() == TBR_DURATION || scripter.getCurrentMenu().getType() == TBR_SET)
                throw new CommandException().message("failed setting basal!");
            retries = 10;
            boolean cancelledError = true;
            if (percentage == 100)
                cancelledError = false;
            while (retries >= 0 && scripter.getCurrentMenu().getType() != MAIN_MENU) {
                // TODO how probable is it, that a totally unrelated error (like occlusion alert)
                // is raised at this point, which we'd cancel together with the TBR cancelled alert?
                if (percentage == 100 && scripter.getCurrentMenu().getType() == WARNING_OR_ERROR) {
                    // TODO extract method confirmAlert(alert)
                    scripter.pressCheckKey();
                    retries++;
                    cancelledError = true;
                    scripter.waitForScreenUpdate(1000);
                } else {
                    retries--;
                    if (scripter.getCurrentMenu().getType() == MAIN_MENU && cancelledError)
                        break;
                }
            }

            log.debug("9. verifying the main menu display the TBR we just set/cancelled");
            if (retries < 0 || scripter.getCurrentMenu().getType() != MAIN_MENU)
                throw new CommandException().message("failed going to main!");

            Object percentageObj = scripter.getCurrentMenu().getAttribute(MenuAttribute.TBR);
            Object durationObj = scripter.getCurrentMenu().getAttribute(MenuAttribute.RUNTIME);

            if (percentage == 100) {
                if (durationObj != null)
                    throw new CommandException().message("TBR cancelled, but main menu shows a running TBR");

                return new CommandResult().success(true).enacted(true).message("TBR was cancelled");
            }

            if (percentageObj == null || !(percentageObj instanceof Double))
                throw new CommandException().message("not percentage");

            if (((double) percentageObj) != percentage)
                throw new CommandException().message("wrong percentage set!");

            if (durationObj == null || !(durationObj instanceof MenuTime))
                throw new CommandException().message("not time");

            MenuTime t = (MenuTime) durationObj;
            if (t.getMinute() + (60 * t.getHour()) > duration || t.getMinute() + (60 * t.getHour()) < duration - 5)
                throw new CommandException().message("wrong time set!");


            return new CommandResult().success(true).enacted(true).message(
                    String.format(Locale.US, "TBR set to %d%% for %d min", percentage, duration));
        } catch (Exception e) {
            log.error("got exception: ", e);
            return new CommandResult().success(false).message(e.getMessage()).exception(e);
        }
    }

    @Override
    public String toString() {
        return "SetTbrCommand{" +
                "percentage=" + percentage +
                ", duration=" + duration +
                '}';
    }
}
