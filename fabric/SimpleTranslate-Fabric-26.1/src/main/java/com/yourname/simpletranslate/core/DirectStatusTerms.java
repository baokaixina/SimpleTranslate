package com.yourname.simpletranslate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic UI/status terms that must stay consistent across direct jobs.
 */
public final class DirectStatusTerms {
    private static final List<Entry> ENTRIES = List.of(
            new Entry("Animate Heroes", "动画英雄", List.of()),
            new Entry("Score Leaderboard", "分数排行榜", List.of("得分排行榜")),
            new Entry("Start Game", "开始游戏", List.of()),
            new Entry("From Here", "从这里开始", List.of()),
            new Entry("Click to TP", "点击传送", List.of("点击传送到")),
            new Entry("Use a Smithing Table to", "使用锻造台来", List.of("Use the Smithing Table to")),
            new Entry("Use a Smithing Table", "使用锻造台", List.of("Use the Smithing Table")),
            new Entry("Smithing Table", "锻造台", List.of()),
            new Entry("Has reduced EXP cost when repairing, combining, or renaming in Anvils",
                    "在铁砧上修复、合并或重命名时，降低经验消耗", List.of()),
            new Entry("when repairing, combining, or renaming in Anvils",
                    "在铁砧上修复、合并或重命名时", List.of()),
            new Entry("in Anvils", "在铁砧中", List.of("in an Anvil", "in Anvil")),
            new Entry("Anvils", "铁砧", List.of("Anvil")),
            new Entry("Activation Item", "激活物品", List.of()),
            new Entry("Mana", "法力", List.of()),
            new Entry("in MAINHAND", "放在主手", List.of("in MainHand", "in Main Hand")),
            new Entry("in OFFHAND", "放在副手", List.of("in OffHand", "in Off Hand")),
            new Entry("MAINHAND", "主手", List.of("MainHand", "Main Hand")),
            new Entry("OFFHAND", "副手", List.of("OffHand", "Off Hand")),
            new Entry("Right-Click", "右键点击", List.of("Right Click", "Right-click")),
            new Entry("Left-Click", "左键点击", List.of("Left Click", "Left-click")),
            new Entry("Hover on skill names to view information", "悬停技能名称查看信息", List.of()),
            new Entry("Hover on their names", "悬停名称查看", List.of("悬停在名称上", "悬停名称查看")),
            new Entry("Abbreviations", "缩写", List.of()),
            new Entry("Siege", "攻城", List.of()),
            new Entry("Enemies", "敌人", List.of()),
            new Entry("Loaded", "已加载", List.of("已装载", "装载完成", "加载完毕")),
            new Entry("Loading", "加载中", List.of("装载中", "正在装载", "正在加载")),
            new Entry("Unloaded", "未加载", List.of("未装载", "尚未加载")),
            new Entry("Enabled", "已启用", List.of("已开启", "已打开")),
            new Entry("Disabled", "已禁用", List.of("已关闭", "已停用")),
            new Entry("Failed", "失败", List.of("已失败")),
            new Entry("Ready", "就绪", List.of("准备就绪")),
            new Entry("Complete", "完成", List.of("已完成")),
            new Entry("Completed", "已完成", List.of("完成"))
    );

    private DirectStatusTerms() {
    }

    public static String apply(String source, String translated) {
        if (translated == null || translated.isEmpty() || source == null || source.isBlank()) {
            return translated;
        }
        String result = translated;
        for (Entry entry : ENTRIES) {
            if (!entry.appearsIn(source)) {
                continue;
            }
            result = entry.sourcePattern().matcher(result).replaceAll(entry.target());
            for (String variant : entry.variants()) {
                result = result.replace(variant, entry.target());
            }
        }
        return result;
    }

    public static String repairUntranslatedLine(String source, String surface) {
        if (source == null || source.isBlank()) {
            return source;
        }
        String effectiveSurface = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        if (!effectiveSurface.startsWith("tooltip.")
                && !effectiveSurface.startsWith("hover.")
                && !effectiveSurface.startsWith("chat.")
                && !effectiveSurface.startsWith("hud.")) {
            return source;
        }
        return apply(source, source);
    }

    /** Plain "source -> target" glossary lines for the Component JSON prompt. */
    public static String plainGlossary(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Entry entry : ENTRIES) {
            if (!entry.appearsIn(source)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.source()).append(" -> ").append(entry.target());
        }
        return builder.toString();
    }

    public static Map<String, String> fixedTermsForTest() {
        return Map.ofEntries(
                Map.entry("Animate Heroes", "动画英雄"),
                Map.entry("Siege", "攻城"),
                Map.entry("Enemies", "敌人"),
                Map.entry("Loaded", "已加载"),
                Map.entry("Loading", "加载中"),
                Map.entry("Enabled", "已启用"),
                Map.entry("Disabled", "已禁用"),
                Map.entry("Smithing Table", "锻造台"),
                Map.entry("Anvils", "铁砧"),
                Map.entry("Activation Item", "激活物品"),
                Map.entry("MAINHAND", "主手"),
                Map.entry("OFFHAND", "副手"),
                Map.entry("Failed", "失败")
        );
    }

    private record Entry(String source, String target, List<String> variants, Pattern sourcePattern) {
        private Entry(String source, String target, List<String> variants) {
            this(source, target, variants, Pattern.compile("(?<![A-Za-z0-9_])"
                    + Pattern.quote(source) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE));
        }

        private boolean appearsIn(String text) {
            return text != null && sourcePattern.matcher(text).find();
        }

        @Override
        public String toString() {
            return source.toLowerCase(Locale.ROOT) + " -> " + target;
        }
    }
}
