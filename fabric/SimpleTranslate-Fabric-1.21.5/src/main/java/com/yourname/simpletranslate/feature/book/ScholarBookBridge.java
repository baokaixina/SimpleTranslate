package com.yourname.simpletranslate.feature.book;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.book.BookTranslationHelper.PageData;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives Scholar's replacement book screens the same whole-page translation the
 * vanilla book screens get, for both reading and editing.
 *
 * <p>Scholar does not extend the vanilla book screens; it renders its own
 * double-page {@code SpreadBookViewScreen} and {@code SpreadBookEditScreen}.
 * Both are handled the way the vanilla mixins handle their counterparts:</p>
 *
 * <ul>
 *   <li><b>Reading</b> substitutes whole {@link FormattedText} pages at the
 *       {@code BookViewAccess} the screen reads from, before Scholar splits
 *       them into visual lines. Scholar therefore re-wraps the translation
 *       itself and a longer or shorter line never overflows the page.</li>
 *   <li><b>Editing</b> substitutes only what is drawn. The translation is
 *       pushed into the text box editor for the duration of one render and
 *       taken back out at the end of it, so the page list the book is saved
 *       from never holds anything but what the player typed. Any edit drops
 *       the translation, exactly like the vanilla book editor.</li>
 * </ul>
 *
 * <p>Everything here goes through reflection, plus one JDK proxy over Scholar's
 * own interface. No mixin is applied to a Scholar class, so a Scholar update
 * that renames an internal method degrades to "no book translation" instead of
 * a failed injection, and nothing needs Scholar on the compile classpath.</p>
 */
public final class ScholarBookBridge {
    private static final String GUI_PACKAGE = "io.github.mortuusars.scholar.client.gui.";
    /** Every Scholar book screen, including the signing variant. */
    private static final String BOOK_SCREEN_PACKAGE = GUI_PACKAGE + "screen.";
    private static final String TEXT_BOX_PACKAGE = GUI_PACKAGE + "widget.textbox.";
    private static final String VIEW_SCREEN_CLASS = BOOK_SCREEN_PACKAGE + "view.SpreadBookViewScreen";
    private static final String ACCESS_INTERFACE = BOOK_SCREEN_PACKAGE + "view.BookViewAccess";
    private static final String BOOK_SCREEN_CLASS = BOOK_SCREEN_PACKAGE + "SpreadBookScreen";
    private static final String EDIT_SCREEN_CLASS = BOOK_SCREEN_PACKAGE + "edit.SpreadBookEditScreen";
    private static final String TEXT_BOX_CLASS = TEXT_BOX_PACKAGE + "TextBox";
    private static final String EDITOR_CLASS = TEXT_BOX_PACKAGE + "text.FormattedStringEditor";
    private static final String STRING_CLASS = TEXT_BOX_PACKAGE + "text.FormattedString";
    private static final String DISPLAY_CACHE_CLASS =
            TEXT_BOX_PACKAGE + "display.FormattedStringDisplayCache";

    /** SpreadBookScreen.BOOK_WIDTH / BOOK_HEIGHT: one open double page. */
    private static final int BOOK_WIDTH = 295;
    private static final int BOOK_HEIGHT = 180;
    /**
     * How much of the book's right edge Scholar's own buttons already own. The
     * reader has one export button at {@code topPos + 16}; the editor stacks
     * import and export at {@code topPos + 16} and {@code topPos + 41}, both
     * 18px tall. The translate tab starts below whichever applies.
     */
    private static final int VIEW_TOOLS_RESERVED_TOP = 42;
    private static final int EDIT_TOOLS_RESERVED_TOP = 65;
    /**
     * A writable book reports a fixed 100-page capacity, so the trailing empty
     * pages are dropped before a request is built. Only the tail is trimmed, so
     * page indices still line up with the translated list.
     */
    private static final int MAX_PAGES = 512;
    /** Distinguishes "the call failed" from a legitimate null return value. */
    private static final Object FAILED = new Object();

    private static final BookFeature FEATURE = new BookFeature();

    private static boolean unavailable;

    // Reading.
    private static boolean viewResolved;
    private static Class<?> viewScreenClass;
    private static Class<?> accessInterface;
    private static Method getBookAccess;
    private static Method setBookAccess;
    private static Method getPageCount;
    private static Method getPage;

    // Editing.
    private static boolean editResolved;
    private static Class<?> editScreenClass;
    private static Class<?> formattedStringClass;
    private static Field pagesField;
    private static Field currentSpreadField;
    private static Field leftTextBoxField;
    private static Field rightTextBoxField;
    private static Method getEditor;
    private static Method getDisplayCache;
    private static Method scheduleUpdate;
    private static Method getEditorString;
    private static Method setEditorString;
    private static Method getCursorPos;
    private static Method setCursorPos;
    private static Method getSelectionAnchor;
    private static Method setSelectionAnchor;
    private static Method parseFormattedString;

    // Session.
    private static Screen boundScreen;
    private static List<Component> originalPages = List.of();
    private static boolean bookmarkMouseDown;
    /** The access a reader's translation was read from; identity, not content. */
    private static Object translatedFromAccess;
    private static Object swappedOriginalAccess;
    private static Object installedProxy;
    /** The page list an editor's translation was read from; content, not identity. */
    private static List<String> translatedFromPages;
    private static final List<EditorSwap> ACTIVE_SWAPS = new ArrayList<>(2);

    private ScholarBookBridge() {
    }

    /**
     * True for any Scholar book screen. Those screens own their text the same
     * way the vanilla book screens do, so the whole-frame GUI capture must not
     * also claim them: it would collect one already wrapped visual line at a
     * time and redraw the result at fixed line positions.
     */
    public static boolean isBookScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(BOOK_SCREEN_PACKAGE);
    }

    /** Installs the translated pages for the frame that is about to render. */
    public static void beginRender(Screen screen) {
        restoreOriginals();
        if (!isBookScreen(screen) || (!isViewScreen(screen) && !isEditScreen(screen))) {
            release();
            return;
        }
        if (screen != boundScreen) {
            release();
            boundScreen = screen;
        }
        if (!enabled() || !FEATURE.active()
                || HoldOriginalState.isHolding(HoldOriginalFeature.BOOK)) {
            return;
        }
        if (isViewScreen(screen)) {
            installTranslatedAccess(screen);
        } else {
            installTranslatedPageText(screen);
        }
    }

    /**
     * Puts Scholar's own text back, then draws the translate bookmark on top of
     * the finished page. The originals have to come back first: starting a
     * translation reads every page through them, Scholar's export button writes
     * those same pages to a file, and the editor's click-to-position mapping
     * has to measure the text the player is actually editing.
     */
    public static void endRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        restoreOriginals();
        if (screen != boundScreen || !enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }
        int reservedTop = isEditScreen(screen) ? EDIT_TOOLS_RESERVED_TOP : VIEW_TOOLS_RESERVED_TOP;
        BookBookmarkControl.renderEdgeTab(graphics, minecraft.font, screen.width, screen.height,
                BOOK_WIDTH, BOOK_HEIGHT, reservedTop, mouseX, mouseY,
                FEATURE.active(), FEATURE.translating());
        handleBookmarkMouse(screen, reservedTop, mouseX, mouseY);
    }

    public static void clearLocalState() {
        restoreOriginals();
        release();
    }

    private static boolean enabled() {
        return ModConfig.GLOBAL_ENABLED.get() && ModConfig.CONTENT_BOOK_ENABLED.get();
    }

    private static void release() {
        boundScreen = null;
        originalPages = List.of();
        translatedFromAccess = null;
        translatedFromPages = null;
        bookmarkMouseDown = false;
        FEATURE.reset();
    }

    private static void restoreOriginals() {
        restoreAccess();
        restoreEditors();
    }

    // ---------------------------------------------------------------- reading

    private static void installTranslatedAccess(Screen screen) {
        Object access = invoke(getBookAccess, screen);
        if (access == null || access == FAILED || Proxy.isProxyClass(access.getClass())) {
            return;
        }
        // A lectern hands its screen a brand new access whenever the book in the
        // block changes. Translating the previous book onto the new one would be
        // silently wrong, so the session is dropped instead.
        if (access != translatedFromAccess) {
            release();
            boundScreen = screen;
            return;
        }
        Object translated = translatedAccess(access);
        if (translated == null || invoke(setBookAccess, screen, translated) == FAILED) {
            return;
        }
        swappedOriginalAccess = access;
        installedProxy = translated;
    }

    /**
     * Puts Scholar's own access back, but only over the proxy this bridge
     * installed. If Scholar replaced it mid-frame the newer object wins.
     */
    private static void restoreAccess() {
        Object access = swappedOriginalAccess;
        Object proxy = installedProxy;
        swappedOriginalAccess = null;
        installedProxy = null;
        if (access == null || boundScreen == null) {
            return;
        }
        if (invoke(getBookAccess, boundScreen) == proxy) {
            invoke(setBookAccess, boundScreen, access);
        }
    }

    /**
     * Wraps Scholar's own access so only the page text changes. Every other
     * call - the book stack, the page count, the golden flag, the bookmarked
     * page - still reaches the real implementation.
     */
    private static Object translatedAccess(Object original) {
        try {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                int parameters = method.getParameterCount();
                if (parameters == 1 && args != null && args[0] instanceof Integer index
                        && ("getPage".equals(name) || "getPageRaw".equals(name))) {
                    Component replacement = translatedPage(index);
                    if (replacement != null) {
                        return replacement;
                    }
                }
                if (parameters == 0 && "hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if (parameters == 0 && "toString".equals(name)) {
                    return "SimpleTranslate translated BookViewAccess";
                }
                if (parameters == 1 && "equals".equals(name)) {
                    return proxy == args[0];
                }
                return method.invoke(original, args);
            };
            return Proxy.newProxyInstance(
                    accessInterface.getClassLoader(), new Class<?>[]{accessInterface}, handler);
        } catch (RuntimeException error) {
            reportUnavailable("could not wrap BookViewAccess", error);
            return null;
        }
    }

    /**
     * Null means "no replacement": the caller then keeps Scholar's own page.
     *
     * <p>Deliberately a {@code Component} and not the wider {@code FormattedText}
     * the pre-26 interface declares. Newer Scholar narrowed {@code getPage} to
     * {@code Component}, and a proxy must return something the declared type
     * accepts, so the narrower type is the one that satisfies both.</p>
     */
    private static Component translatedPage(int index) {
        List<Component> translated = FEATURE.translatedPages();
        if (translated != null && index >= 0 && index < translated.size()) {
            return translated.get(index);
        }
        if (FEATURE.translating() && FEATURE.pageNeedsTranslation(index)
                && index >= 0 && index < originalPages.size()) {
            return BookTranslationHelper.buildTranslatingComponent(originalPages.get(index));
        }
        return null;
    }

    /** Reads the whole book once, without the empty tail a writable book reports. */
    private static List<FormattedText> readPages(Screen screen) {
        Object access = invoke(getBookAccess, screen);
        if (access == null || access == FAILED) {
            return List.of();
        }
        Object count = invoke(getPageCount, access);
        if (!(count instanceof Integer pageCount)) {
            return List.of();
        }
        List<FormattedText> pages = new ArrayList<>();
        int limit = Math.min(pageCount, MAX_PAGES);
        int lastWithContent = -1;
        for (int index = 0; index < limit; index++) {
            Object page = invoke(getPage, access, index);
            if (!(page instanceof FormattedText text)) {
                return List.of();
            }
            pages.add(text);
            if (!text.getString().isBlank()) {
                lastWithContent = index;
            }
        }
        return lastWithContent < 0 ? List.of() : List.copyOf(pages.subList(0, lastWithContent + 1));
    }

    // ---------------------------------------------------------------- editing

    /**
     * Shows the translation without letting the book keep it.
     *
     * <p>Scholar's text box owns a {@code FormattedString} that its edit
     * handlers write straight back into the page list the book is saved from.
     * Pushing a translation in through the editing API would therefore save the
     * translation as the player's own writing. Instead the editor's string is
     * replaced for exactly the length of one render and taken back out in
     * {@link #restoreEditors()}: the display cache rebuilds from the
     * translation while it is in, and every edit, click and save that happens
     * outside the render still sees only the original.</p>
     */
    private static void installTranslatedPageText(Screen screen) {
        List<String> pages = readEditorPages(screen);
        if (pages == null || !pages.equals(translatedFromPages)) {
            // Typing, adding or removing a page invalidates the translation,
            // the same way the vanilla book editor drops it on any edit.
            release();
            boundScreen = screen;
            return;
        }
        Object spread = readField(currentSpreadField, screen);
        if (!(spread instanceof Integer currentSpread)) {
            return;
        }
        swapEditor(readField(leftTextBoxField, screen), currentSpread * 2);
        swapEditor(readField(rightTextBoxField, screen), currentSpread * 2 + 1);
    }

    private static void swapEditor(Object textBox, int pageIndex) {
        if (textBox == null || textBox == FAILED) {
            return;
        }
        String display = editorDisplayText(pageIndex);
        if (display == null) {
            return;
        }
        Object editor = invoke(getEditor, textBox);
        Object displayCache = invoke(getDisplayCache, textBox);
        if (editor == null || editor == FAILED || displayCache == null || displayCache == FAILED) {
            return;
        }
        Object original = invoke(getEditorString, editor);
        Object cursor = invoke(getCursorPos, editor);
        Object anchor = invoke(getSelectionAnchor, editor);
        Object translated = invoke(parseFormattedString, null, display);
        if (original == FAILED || translated == null || translated == FAILED
                || !(cursor instanceof Integer cursorPos)
                || !(anchor instanceof Integer anchorPos)) {
            return;
        }
        if (invoke(setEditorString, editor, translated) == FAILED) {
            return;
        }
        ACTIVE_SWAPS.add(new EditorSwap(editor, displayCache, original, cursorPos, anchorPos));
        // The translation is a different length, so the caret has to be pulled
        // back inside it before the cache measures a line for it. setCursorPos
        // clamps against the string that is installed right now and drops the
        // selection, which also keeps the highlight maths in range.
        invoke(setCursorPos, editor, cursorPos, false);
        invoke(scheduleUpdate, displayCache);
    }

    private static void restoreEditors() {
        if (ACTIVE_SWAPS.isEmpty()) {
            return;
        }
        List<EditorSwap> swaps = List.copyOf(ACTIVE_SWAPS);
        ACTIVE_SWAPS.clear();
        for (EditorSwap swap : swaps) {
            invoke(setEditorString, swap.editor(), swap.original());
            invoke(setCursorPos, swap.editor(), swap.cursor(), false);
            invoke(setSelectionAnchor, swap.editor(), swap.anchor());
            invoke(scheduleUpdate, swap.displayCache());
        }
    }

    /** Mirrors the vanilla book editor: untranslatable pages keep their own text. */
    private static String editorDisplayText(int pageIndex) {
        if (!FEATURE.pageNeedsTranslation(pageIndex)) {
            return null;
        }
        List<Component> translated = FEATURE.translatedPages();
        if (translated != null && pageIndex >= 0 && pageIndex < translated.size()) {
            return translated.get(pageIndex).getString();
        }
        return FEATURE.translating() ? BookTranslationHelper.getTranslatingText() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readEditorPages(Screen screen) {
        Object pages = readField(pagesField, screen);
        if (!(pages instanceof List<?> list)) {
            return null;
        }
        for (Object page : list) {
            if (!(page instanceof String)) {
                return null;
            }
        }
        return List.copyOf((List<String>) list);
    }

    private record EditorSwap(Object editor, Object displayCache,
                              Object original, int cursor, int anchor) {
    }

    // ---------------------------------------------------------------- control

    private static void handleBookmarkMouse(Screen screen, int reservedTop, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        boolean down = GLFW.glfwGetMouseButton(
                minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !bookmarkMouseDown
                && BookBookmarkControl.isMouseOverEdgeTab(
                        screen.width, screen.height, BOOK_WIDTH, BOOK_HEIGHT, reservedTop,
                        mouseX, mouseY)) {
            if (FEATURE.active()) {
                FEATURE.reset();
            } else if (isEditScreen(screen)) {
                startEditTranslation(screen);
            } else {
                startViewTranslation(screen);
            }
        }
        bookmarkMouseDown = down;
    }

    private static void startViewTranslation(Screen screen) {
        List<FormattedText> pages = readPages(screen);
        if (pages.isEmpty()) {
            return;
        }
        List<PageData> pageData = BookTranslationHelper.buildPageDataFromFormatted(pages);
        if (!begin(screen, pageData)) {
            return;
        }
        translatedFromAccess = invoke(getBookAccess, screen);
    }

    private static void startEditTranslation(Screen screen) {
        List<String> pages = readEditorPages(screen);
        if (pages == null || pages.isEmpty()) {
            return;
        }
        List<PageData> pageData = BookTranslationHelper.buildPageDataFromStrings(pages);
        if (!begin(screen, pageData)) {
            return;
        }
        translatedFromPages = pages;
    }

    private static boolean begin(Screen screen, List<PageData> pageData) {
        if (pageData == null || !FEATURE.start(pageData)) {
            release();
            boundScreen = screen;
            return false;
        }
        List<Component> originals = new ArrayList<>(pageData.size());
        for (PageData page : pageData) {
            originals.add(page == null || page.originalComponent == null
                    ? Component.empty() : page.originalComponent);
        }
        originalPages = List.copyOf(originals);
        return true;
    }

    // ------------------------------------------------------------- reflection

    private static boolean isViewScreen(Screen screen) {
        return screen != null && isBookScreen(screen) && resolveView(screen)
                && viewScreenClass.isInstance(screen);
    }

    private static boolean isEditScreen(Screen screen) {
        return screen != null && isBookScreen(screen) && resolveEdit(screen)
                && editScreenClass.isInstance(screen);
    }

    private static boolean resolveView(Screen screen) {
        if (viewResolved) {
            return viewScreenClass != null;
        }
        viewResolved = true;
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            Class<?> screenClass = Class.forName(VIEW_SCREEN_CLASS, false, loader);
            accessInterface = Class.forName(ACCESS_INTERFACE, false, loader);
            getBookAccess = screenClass.getMethod("getBookAccess");
            setBookAccess = screenClass.getMethod("setBookAccess", accessInterface);
            getPageCount = accessInterface.getMethod("getPageCount");
            getPage = accessInterface.getMethod("getPage", int.class);
            viewScreenClass = screenClass;
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportUnavailable("Scholar's book reader is not the expected shape", error);
            return false;
        }
        return true;
    }

    private static boolean resolveEdit(Screen screen) {
        if (editResolved) {
            return editScreenClass != null;
        }
        editResolved = true;
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            Class<?> screenClass = Class.forName(EDIT_SCREEN_CLASS, false, loader);
            Class<?> bookScreenClass = Class.forName(BOOK_SCREEN_CLASS, false, loader);
            Class<?> textBoxClass = Class.forName(TEXT_BOX_CLASS, false, loader);
            Class<?> editorClass = Class.forName(EDITOR_CLASS, false, loader);
            Class<?> cacheClass = Class.forName(DISPLAY_CACHE_CLASS, false, loader);
            formattedStringClass = Class.forName(STRING_CLASS, false, loader);

            pagesField = accessible(screenClass.getDeclaredField("pages"));
            leftTextBoxField = accessible(screenClass.getDeclaredField("leftPageTextBox"));
            rightTextBoxField = accessible(screenClass.getDeclaredField("rightPageTextBox"));
            currentSpreadField = accessible(bookScreenClass.getDeclaredField("currentSpread"));

            getEditor = textBoxClass.getMethod("getEditor");
            getDisplayCache = textBoxClass.getMethod("getDisplayCache");
            scheduleUpdate = cacheClass.getMethod("scheduleUpdate");
            getEditorString = editorClass.getMethod("getString");
            setEditorString = editorClass.getMethod("setString", formattedStringClass);
            getCursorPos = editorClass.getMethod("getCursorPos");
            setCursorPos = editorClass.getMethod("setCursorPos", int.class, boolean.class);
            getSelectionAnchor = editorClass.getMethod("getSelectionAnchor");
            setSelectionAnchor = editorClass.getMethod("setSelectionAnchor", int.class);
            parseFormattedString = formattedStringClass.getMethod("parse", String.class);
            editScreenClass = screenClass;
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportUnavailable("Scholar's book editor is not the expected shape", error);
            return false;
        }
        return true;
    }

    private static Field accessible(Field field) throws ReflectiveOperationException {
        field.setAccessible(true);
        return field;
    }

    private static Object readField(Field field, Object target) {
        if (field == null || target == null) {
            return FAILED;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportUnavailable("reading " + field.getName() + " failed", error);
            return FAILED;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        if (method == null) {
            return FAILED;
        }
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportUnavailable("call to " + method.getName() + " failed", error);
            return FAILED;
        }
    }

    private static void reportUnavailable(String reason, Throwable error) {
        if (unavailable) {
            return;
        }
        unavailable = true;
        SimpleTranslateMod.getLogger().warn(
                "Scholar book translation disabled for this session: {} ({})",
                reason, error == null ? "none" : error.getClass().getSimpleName());
    }
}
