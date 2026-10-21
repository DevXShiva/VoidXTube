package com.newtube.mobile.ui.settings;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One row of a phone Settings page (issue #2). A page is a plain list of these, built fresh every
 * time it is shown, so values read straight from the prefs classes and never go stale.
 *
 * <p>Kinds, in YouTube's and LibreTube's vocabulary:</p>
 * <ul>
 *     <li>{@link #KIND_HEADER}: a group title inside a page;</li>
 *     <li>{@link #KIND_LINK}: opens another page, a sheet or a link (optional leading icon and
 *     summary);</li>
 *     <li>{@link #KIND_SWITCH}: an on/off setting, title + one line of what it does;</li>
 *     <li>{@link #KIND_CHOICE}: one row whose summary is the current value; a tap opens a dialog
 *     with the options, and picking one applies it and closes the dialog. This replaces the old
 *     inline radio lists that made a page twenty rows longer per setting;</li>
 *     <li>{@link #KIND_NOTE}: a small paragraph that explains the rows above it;</li>
 *     <li>{@link #KIND_DIVIDER}: the hairline between two groups.</li>
 * </ul>
 */
public final class SettingsRow {
    public static final int KIND_HEADER = 0;
    public static final int KIND_LINK = 1;
    public static final int KIND_SWITCH = 2;
    public static final int KIND_CHOICE = 3;
    public static final int KIND_NOTE = 4;
    public static final int KIND_DIVIDER = 5;
    /** VOIDXDEV(support): a centred thank-you at the foot of a page - icon, a line, a small link. */
    public static final int KIND_FOOTER = 6;

    /** What a toggle or a choice does after it has been applied. */
    public interface Toggle {
        void set(boolean on);
    }

    public interface Pick<T> {
        void pick(T value);
    }

    /** A link's action, handed the page so it can open dialogs and refresh itself. */
    public interface Action {
        void run(@NonNull SettingsPageFragment page);
    }

    public static final class Option {
        final CharSequence label;
        @Nullable final CharSequence description;
        final Object value;
        /** A preview beside the label (the app icon picker), 0 for none. */
        @DrawableRes int icon;

        Option(CharSequence label, @Nullable CharSequence description, Object value) {
            this.label = label;
            this.description = description;
            this.value = value;
        }
    }

    final int kind;
    @DrawableRes int icon;
    CharSequence title;
    @Nullable Supplier<CharSequence> summary;
    @Nullable String page;
    @Nullable Action action;
    @Nullable BooleanSupplier checked;
    @Nullable Toggle toggle;
    @Nullable List<Option> options;
    @Nullable Supplier<Object> current;
    @Nullable Pick<Object> pick;
    @Nullable BooleanSupplier enabled;
    /** The change only lands after the app restarts: offer the restart. */
    boolean restart;
    /** Red title: an action that deletes something. */
    boolean destructive;

    private SettingsRow(int kind) {
        this.kind = kind;
    }

    public static SettingsRow header(CharSequence title) {
        SettingsRow row = new SettingsRow(KIND_HEADER);
        row.title = title;
        return row;
    }

    public static SettingsRow divider() {
        return new SettingsRow(KIND_DIVIDER);
    }

    public static SettingsRow footer(@DrawableRes int icon, CharSequence title, CharSequence summary,
                                     @NonNull Action action) {
        SettingsRow row = new SettingsRow(KIND_FOOTER);
        row.icon = icon;
        row.title = title;
        row.summary = () -> summary;
        row.action = action;
        return row;
    }

    public static SettingsRow note(CharSequence text) {
        SettingsRow row = new SettingsRow(KIND_NOTE);
        row.title = text;
        return row;
    }

    /** A row that opens another page of the tree. */
    public static SettingsRow page(@DrawableRes int icon, CharSequence title, @Nullable CharSequence summary,
                                   @NonNull String pageId) {
        SettingsRow row = new SettingsRow(KIND_LINK);
        row.icon = icon;
        row.title = title;
        row.summary = summary != null ? () -> summary : null;
        row.page = pageId;
        return row;
    }

    public static SettingsRow page(CharSequence title, @Nullable Supplier<CharSequence> summary, @NonNull String pageId) {
        SettingsRow row = new SettingsRow(KIND_LINK);
        row.title = title;
        row.summary = summary;
        row.page = pageId;
        return row;
    }

    /** A row that does something: opens a sheet, a link, a confirmation. */
    public static SettingsRow action(CharSequence title, @Nullable CharSequence summary, @NonNull Action action) {
        return action(0, title, summary != null ? () -> summary : null, action);
    }

    public static SettingsRow action(@DrawableRes int icon, CharSequence title, @Nullable Supplier<CharSequence> summary,
                                     @NonNull Action action) {
        SettingsRow row = new SettingsRow(KIND_LINK);
        row.icon = icon;
        row.title = title;
        row.summary = summary;
        row.action = action;
        return row;
    }

    public static SettingsRow toggle(CharSequence title, @Nullable CharSequence summary,
                                     @NonNull BooleanSupplier checked, @NonNull Toggle toggle) {
        SettingsRow row = new SettingsRow(KIND_SWITCH);
        row.title = title;
        row.summary = summary != null ? () -> summary : null;
        row.checked = checked;
        row.toggle = toggle;
        return row;
    }

    public static <T> Choice<T> choice(CharSequence title) {
        return new Choice<>(title);
    }

    /**
     * One of the shared option lists (the radio categories of {@code AppDialogUtil} and the
     * presenters, which carry their own selection logic) as a single choice row. The page is
     * rebuilt after a pick, so the list is created again with the new selection.
     */
    public static SettingsRow fromRadio(@NonNull OptionCategory category, @Nullable CharSequence title) {
        SettingsRow row = new SettingsRow(KIND_CHOICE);
        row.title = title != null ? title : category.title;
        List<Option> options = new ArrayList<>();
        OptionItem selected = null;
        if (category.options != null) {
            for (OptionItem item : category.options) {
                if (item == null) {
                    continue;
                }
                options.add(new Option(item.getTitle(), item.getDescription(), item));
                if (selected == null && item.isSelected()) {
                    selected = item;
                }
            }
        }
        OptionItem current = selected;
        row.options = options;
        row.current = () -> current;
        row.pick = value -> ((OptionItem) value).onSelect(true);
        return row;
    }

    /** One option of a shared checkbox list as a switch row. */
    public static SettingsRow fromCheck(@NonNull OptionItem item, @Nullable CharSequence summary) {
        return toggle(item.getTitle(), summary != null ? summary : item.getDescription(),
                item::isSelected, item::onSelect);
    }

    public SettingsRow enabledWhen(@NonNull BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    public SettingsRow needsRestart() {
        this.restart = true;
        return this;
    }

    public SettingsRow destructive() {
        this.destructive = true;
        return this;
    }

    public SettingsRow summary(@Nullable Supplier<CharSequence> summary) {
        this.summary = summary;
        return this;
    }

    boolean isEnabled() {
        return enabled == null || enabled.getAsBoolean();
    }

    @Nullable
    CharSequence summaryText() {
        if (kind == KIND_CHOICE && summary == null) {
            Option option = currentOption();
            return option != null ? option.label : null;
        }
        return summary != null ? summary.get() : null;
    }

    int currentIndex() {
        if (options == null || current == null) {
            return -1;
        }
        Object value = current.get();
        for (int i = 0; i < options.size(); i++) {
            if (Objects.equals(options.get(i).value, value)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    Option currentOption() {
        int index = currentIndex();
        return index >= 0 ? options.get(index) : null;
    }

    /**
     * Builds a {@link #KIND_CHOICE} row: options with their values, how to read the current one,
     * what a pick does.
     */
    public static final class Choice<T> {
        private final SettingsRow mRow = new SettingsRow(KIND_CHOICE);
        private final List<Option> mOptions = new ArrayList<>();

        private Choice(CharSequence title) {
            mRow.title = title;
        }

        public Choice<T> option(CharSequence label, T value) {
            mOptions.add(new Option(label, null, value));
            return this;
        }

        public Choice<T> option(CharSequence label, @Nullable CharSequence description, T value) {
            mOptions.add(new Option(label, description, value));
            return this;
        }

        /** An option with a preview image beside its label. */
        public Choice<T> option(CharSequence label, @DrawableRes int icon, T value) {
            Option option = new Option(label, null, value);
            option.icon = icon;
            mOptions.add(option);
            return this;
        }

        public Choice<T> option(Context context, int labelRes, T value) {
            return option(context.getString(labelRes), value);
        }

        /** A fixed summary (an explanation) instead of the current option's label. */
        public Choice<T> summary(CharSequence summary) {
            mRow.summary = () -> summary;
            return this;
        }

        @SuppressWarnings("unchecked")
        public SettingsRow bind(@NonNull Supplier<T> current, @NonNull Pick<T> pick) {
            mRow.options = mOptions;
            mRow.current = (Supplier<Object>) current;
            mRow.pick = value -> pick.pick((T) value);
            return mRow;
        }
    }
}
