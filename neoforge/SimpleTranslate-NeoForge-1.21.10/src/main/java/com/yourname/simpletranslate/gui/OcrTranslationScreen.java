package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.config.ModConfig.ApiFormat;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.translation.VisionOcrTranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public class OcrTranslationScreen extends ScrollableSettingsScreen {
    private boolean currentEnabled;
    private boolean currentUseTranslationModel;
    private boolean currentHistoryEnabled;
    private ApiFormat currentApiFormat;
    private String currentApiUrl;
    private String currentApiKey;
    private String currentModel;
    private int currentRegionWidth;
    private int currentRegionHeight;
    private String status = "";
    private boolean checking;

    private CycleButton<Boolean> enabledButton;
    private CycleButton<Boolean> useTranslationModelButton;
    private CycleButton<Boolean> historyEnabledButton;
    private Button historyButton;
    private CycleButton<ApiFormat> apiFormatButton;
    private EditBox apiUrlInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox widthInput;
    private EditBox heightInput;
    private Button checkButton;

    public OcrTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.ocr_settings"), parent);
        this.currentEnabled = ModConfig.OCR_ENABLED.get();
        this.currentUseTranslationModel = ModConfig.OCR_USE_TRANSLATION_MODEL.get();
        this.currentHistoryEnabled = ModConfig.OCR_HISTORY_ENABLED.get();
        this.currentApiFormat = visionFormatOrDefault(ModConfig.OCR_API_FORMAT.get());
        this.currentApiUrl = ModConfig.OCR_API_URL.get();
        this.currentApiKey = ModConfig.OCR_API_KEY.get();
        this.currentModel = ModConfig.OCR_MODEL.get();
        this.currentRegionWidth = ModConfig.OCR_REGION_WIDTH.get();
        this.currentRegionHeight = ModConfig.OCR_REGION_HEIGHT.get();
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.ocr.section.general"));

        this.enabledButton = CycleButton.onOffBuilder(this.currentEnabled)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.ocr.enabled"),
                        (button, value) -> this.currentEnabled = value);
        withTooltip(this.enabledButton, "screen.simple_translate.ocr.enabled.tooltip");
        addEntry(this.enabledButton);

        this.useTranslationModelButton = CycleButton.onOffBuilder(this.currentUseTranslationModel)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.ocr.use_translation_model"),
                        (button, value) -> {
                            this.currentUseTranslationModel = value;
                            rebuildScreen();
                        });
        withTooltip(this.useTranslationModelButton, "screen.simple_translate.ocr.use_translation_model.tooltip");
        addEntry(this.useTranslationModelButton);

        addSectionHeader(text("screen.simple_translate.ocr.section.history"));

        this.historyEnabledButton = CycleButton.onOffBuilder(this.currentHistoryEnabled)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.ocr.history_enabled"),
                        (button, value) -> this.currentHistoryEnabled = value);
        withTooltip(this.historyEnabledButton, "screen.simple_translate.ocr.history_enabled.tooltip");
        addEntry(this.historyEnabledButton);

        this.historyButton = Button.builder(
                Component.translatable("screen.simple_translate.ocr.history_open"),
                button -> Minecraft.getInstance().setScreen(new OcrHistoryScreen(this)))
                .bounds(0, 0, this.contentWidth, 20)
                .build();
        withTooltip(this.historyButton, "screen.simple_translate.ocr.history_open.tooltip");
        addEntry(this.historyButton);

        addSectionHeader(text("screen.simple_translate.ocr.section.model"));


        this.apiUrlInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.ocr.api_url"));
        this.apiUrlInput.setMaxLength(512);
        this.apiUrlInput.setValue(this.currentApiUrl);
        this.apiUrlInput.setHint(Component.translatable("screen.simple_translate.ocr.api_url_hint"));
        withTooltip(this.apiUrlInput, "screen.simple_translate.ocr.api_url.tooltip");
        addEntry(this.apiUrlInput);

        this.apiKeyInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.ocr.api_key"));
        this.apiKeyInput.setMaxLength(512);
        this.apiKeyInput.setValue(this.currentApiKey);
        this.apiKeyInput.setHint(Component.translatable("screen.simple_translate.ocr.api_key_hint"));
        this.apiKeyInput.addFormatter((value, pos) -> value.isEmpty()
                ? FormattedCharSequence.EMPTY
                : FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        withTooltip(this.apiKeyInput, "screen.simple_translate.ocr.api_key.tooltip");
        addEntry(this.apiKeyInput);

        this.modelInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.ocr.model"));
        this.modelInput.setMaxLength(256);
        this.modelInput.setValue(this.currentModel);
        this.modelInput.setHint(Component.translatable("screen.simple_translate.ocr.model_hint"));
        withTooltip(this.modelInput, "screen.simple_translate.ocr.model.tooltip");
        addEntry(this.modelInput);

        this.apiFormatButton = CycleButton.<ApiFormat>builder(format -> Component.literal(format.getDisplayName()))
                .withValues(ApiFormat.OPENAI_RESPONSES, ApiFormat.OPENAI_CHAT_COMPAT,
                        ApiFormat.ANTHROPIC_MESSAGES, ApiFormat.GEMINI_GENERATE_CONTENT)
                .withInitialValue(this.currentApiFormat)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.ocr.api_format"),
                        (button, value) -> {
                            this.currentApiFormat = value;
                            if (this.modelInput != null && (this.modelInput.getValue() == null
                                    || this.modelInput.getValue().isBlank())) {
                                this.modelInput.setValue(value.getDefaultModel());
                            }
                        });
        withTooltip(this.apiFormatButton, "screen.simple_translate.ocr.api_format.tooltip");
        addEntry(this.apiFormatButton);

        Component checkLabel = this.checking
                ? Component.translatable("screen.simple_translate.ocr.checking")
                : (this.status.isBlank()
                        ? Component.translatable("screen.simple_translate.ocr.check")
                        : Component.literal(shortText(this.status)));
        this.checkButton = Button.builder(checkLabel, button -> checkOcrApi())
                .bounds(0, 0, this.contentWidth, 20)
                .build();
        withTooltip(this.checkButton, this.status.isBlank()
                ? Component.translatable("screen.simple_translate.ocr.check.tooltip")
                : Component.literal(this.status));
        addEntry(this.checkButton);

        addSectionHeader(text("screen.simple_translate.ocr.section.region"));

        this.widthInput = numericInput("screen.simple_translate.ocr.region_width",
                "screen.simple_translate.ocr.region_width_hint", this.currentRegionWidth);
        withTooltip(this.widthInput, "screen.simple_translate.ocr.region_width.tooltip");
        addEntry(this.widthInput);

        this.heightInput = numericInput("screen.simple_translate.ocr.region_height",
                "screen.simple_translate.ocr.region_height_hint", this.currentRegionHeight);
        withTooltip(this.heightInput, "screen.simple_translate.ocr.region_height.tooltip");
        addEntry(this.heightInput);
    }

    @Override
    protected void repositionEntries() {
        super.repositionEntries();
        if (this.apiUrlInput != null) {
            this.apiUrlInput.active = this.apiUrlInput.visible && !this.checking;
        }
        if (this.apiKeyInput != null) {
            this.apiKeyInput.active = this.apiKeyInput.visible && !this.checking;
        }
        if (this.modelInput != null) {
            this.modelInput.active = this.modelInput.visible && !this.checking;
        }
        if (this.apiFormatButton != null) {
            this.apiFormatButton.active = this.apiFormatButton.visible && !this.checking;
        }
        if (this.checkButton != null) {
            this.checkButton.active = this.checkButton.visible && !this.checking;
        }
    }

    @Override
    protected void saveSettings() {
        captureInputs();
        ModConfig.OCR_ENABLED.set(this.currentEnabled);
        ModConfig.OCR_USE_TRANSLATION_MODEL.set(this.currentUseTranslationModel);
        ModConfig.OCR_HISTORY_ENABLED.set(this.currentHistoryEnabled);
        ModConfig.OCR_API_FORMAT.set(visionFormatOrDefault(this.currentApiFormat));
        ModConfig.OCR_API_URL.set(ModConfig.normalizeApiUrl(this.currentApiUrl));
        ModConfig.OCR_API_KEY.set(this.currentApiKey);
        ModConfig.OCR_MODEL.set(this.currentModel == null || this.currentModel.isBlank()
                ? this.currentApiFormat.getDefaultModel()
                : this.currentModel);
        ModConfig.OCR_REGION_WIDTH.set(this.currentRegionWidth);
        ModConfig.OCR_REGION_HEIGHT.set(this.currentRegionHeight);
    }

    private EditBox numericInput(String labelKey, String hintKey, int value) {
        EditBox input = new EditBox(this.font, 0, 0, this.contentWidth, 20, Component.translatable(labelKey));
        input.setMaxLength(6);
        input.setValue(String.valueOf(value));
        input.setHint(Component.translatable(hintKey));
        input.setFilter(text -> text == null || text.isEmpty() || text.matches("\\d{0,6}"));
        return input;
    }

    private void captureInputs() {
        if (this.apiUrlInput != null) {
            this.currentApiUrl = this.apiUrlInput.getValue() == null ? "" : this.apiUrlInput.getValue().trim();
        }
        if (this.apiKeyInput != null) {
            this.currentApiKey = this.apiKeyInput.getValue() == null ? "" : this.apiKeyInput.getValue().trim();
        }
        if (this.modelInput != null) {
            this.currentModel = this.modelInput.getValue() == null ? "" : this.modelInput.getValue().trim();
        }
        if (this.widthInput != null) {
            this.currentRegionWidth = parseClamped(this.widthInput.getValue(), 420, 80, 1600);
        }
        if (this.heightInput != null) {
            this.currentRegionHeight = parseClamped(this.heightInput.getValue(), 180, 40, 900);
        }
    }

    private void rebuildScreen() {
        captureInputs();
        this.clearWidgets();
        this.init();
    }

    private void checkOcrApi() {
        if (this.checking) {
            return;
        }
        captureInputs();
        this.checking = true;
        this.status = text("screen.simple_translate.ocr.checking");
        rebuildScreen();
        new VisionOcrTranslationService()
                .verifyAccess(this.currentUseTranslationModel, this.currentApiFormat,
                        this.currentApiUrl, this.currentApiKey, this.currentModel)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> finishCheck(result)))
                .exceptionally(error -> {
                    Minecraft.getInstance().execute(() -> {
                        this.checking = false;
                        this.status = text("screen.simple_translate.ocr.status.failed",
                                shortText(error == null ? "unknown" : error.getMessage()));
                        rebuildScreen();
                    });
                    return null;
                });
    }

    private void finishCheck(OcrTranslationService.OcrResult result) {
        this.checking = false;
        if (result != null && result.success()) {
            this.status = text("screen.simple_translate.ocr.status.available");
        } else {
            this.status = text("screen.simple_translate.ocr.status.failed",
                    shortText(result == null ? "unknown" : result.errorMessage()));
        }
        rebuildScreen();
    }

    private String shortText(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (this.currentApiKey != null && !this.currentApiKey.isBlank()) {
            compact = compact.replace(this.currentApiKey, "***");
        }
        return compact.length() > 72 ? compact.substring(0, 69) + "..." : compact;
    }

    private static ApiFormat visionFormatOrDefault(ApiFormat value) {
        return switch (value) {
            case OPENAI_CHAT_COMPAT, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT -> value;
            default -> ApiFormat.OPENAI_RESPONSES;
        };
    }

    private static int parseClamped(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value == null || value.isBlank() ? String.valueOf(fallback) : value);
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
