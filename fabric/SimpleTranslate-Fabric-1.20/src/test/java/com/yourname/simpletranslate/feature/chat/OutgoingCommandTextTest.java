package com.yourname.simpletranslate.feature.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingCommandTextTest {

    private static String translate(String command, String... translations) {
        return translate(OutgoingCommandText.findTranslatableSegments(command), command, translations);
    }

    private static String translate(List<OutgoingCommandText.Segment> segments,
                                    String command, String... translations) {
        assertEquals(translations.length, segments.size(),
                "segment count for " + command + " -> " + OutgoingCommandText.texts(segments));
        return OutgoingCommandText.rebuild(command, segments, List.of(translations));
    }

    @Test
    void quotedTellrawMessageIsTheOnlyTranslatedPart() {
        assertEquals(List.of("你好"),
                OutgoingCommandText.texts(
                        OutgoingCommandText.findTranslatableSegments("/tellraw @s \"你好\"")));
        assertEquals("/tellraw @s \"Hello\"", translate("/tellraw @s \"你好\"", "Hello"));
    }

    @Test
    void jsonKeysAndIdentifiersSurviveUntouched() {
        String command = "/tellraw @s {\"text\":\"你好\",\"color\":\"gold\",\"bold\":true}";

        assertEquals(List.of("你好"),
                OutgoingCommandText.texts(OutgoingCommandText.findTranslatableSegments(command)));
        assertEquals("/tellraw @s {\"text\":\"Hello\",\"color\":\"gold\",\"bold\":true}",
                translate(command, "Hello"));
    }

    @Test
    void translationKeysAndUrlsAreNotProse() {
        String command = "/tellraw @s {\"translate\":\"chat.type.text\",\"clickEvent\":"
                + "{\"action\":\"open_url\",\"value\":\"https://example.com\"}}";

        assertTrue(OutgoingCommandText.findTranslatableSegments(command).isEmpty());
    }

    @Test
    void freeTextCommandsTranslateTheirWholeTail() {
        assertEquals("/say Hello everyone", translate("/say 大家好", "Hello everyone"));
        assertEquals("/me waves", translate("/me 挥手", "waves"));
    }

    @Test
    void whisperKeepsItsTargetArgument() {
        assertEquals(List.of("你好"),
                OutgoingCommandText.texts(
                        OutgoingCommandText.findTranslatableSegments("/msg Steve 你好")));
        assertEquals("/msg Steve Hello", translate("/msg Steve 你好", "Hello"));
    }

    @Test
    void bracketedSelectorCountsAsOneArgument() {
        assertEquals("/tell @a[distance=..10] Hello",
                translate("/tell @a[distance=..10] 你好", "Hello"));
    }

    @Test
    void nestedSnbtComponentIsReachedThroughItsWrapper() {
        String command = "/give @s written_book{title:'{\"text\":\"你好\"}'}";

        assertEquals(List.of("你好"),
                OutgoingCommandText.texts(OutgoingCommandText.findTranslatableSegments(command)));
        assertEquals("/give @s written_book{title:'{\"text\":\"Hello\"}'}",
                translate(command, "Hello"));
    }

    @Test
    void nestedTranslationsDropQuotesInsteadOfDoubleEscaping() {
        String command = "/give @s written_book{title:'{\"text\":\"你好\"}'}";

        assertEquals("/give @s written_book{title:'{\"text\":\"say hi\"}'}",
                translate(command, "say \"hi\""));
    }

    @Test
    void topLevelQuotesAreEscapedRatherThanDropped() {
        assertEquals("/tellraw @s \"say \\\"hi\\\"\"",
                translate("/tellraw @s \"你好\"", "say \"hi\""));
    }

    @Test
    void newlinesNeverLeakIntoTheCommand() {
        assertEquals("/say a b", translate("/say 甲乙", "a\nb"));
    }

    @Test
    void commandsWithoutProseAreLeftAlone() {
        assertTrue(OutgoingCommandText.findTranslatableSegments("/gamemode creative").isEmpty());
        assertTrue(OutgoingCommandText.findTranslatableSegments("/tp @s 100 64 -200").isEmpty());
        assertTrue(OutgoingCommandText.findTranslatableSegments("/give @s minecraft:stone 64").isEmpty());
        assertTrue(OutgoingCommandText.findTranslatableSegments("/say").isEmpty());
        assertTrue(OutgoingCommandText.findTranslatableSegments("/msg Steve").isEmpty());
    }

    @Test
    void plainChatIsNotTreatedAsACommand() {
        assertTrue(OutgoingCommandText.findTranslatableSegments("hello there").isEmpty());
        assertTrue(OutgoingCommandText.findTranslatableSegments("/").isEmpty());
    }

    @Test
    void unbalancedQuotesAreIgnoredRatherThanGuessed() {
        assertTrue(OutgoingCommandText.findTranslatableSegments("/tellraw @s \"你好").isEmpty());
    }

    @Test
    void detectionTextMirrorsWhatWillBeTranslated() {
        String command = "/tellraw @s [\"你好\",\" \",\"再见\"]";
        List<OutgoingCommandText.Segment> segments =
                OutgoingCommandText.findTranslatableSegments(command);

        assertEquals(List.of("你好", "再见"), OutgoingCommandText.texts(segments));
        assertEquals("你好\n再见", OutgoingCommandText.joinForDetection(segments));
        assertEquals("/tellraw @s [\"Hi\",\" \",\"Bye\"]",
                OutgoingCommandText.rebuild(command, segments, List.of("Hi", "Bye")));
    }

    @Test
    void serverChatCommandsComeFromSettings() {
        Map<String, Integer> configured = OutgoingCommandText.parseChatCommands("pc, gc r");

        assertTrue(OutgoingCommandText.findTranslatableSegments("/pc 你好").isEmpty());
        assertEquals(List.of("你好"), OutgoingCommandText.texts(
                OutgoingCommandText.findTranslatableSegments("/pc 你好", configured)));
        assertEquals(List.of("你好"), OutgoingCommandText.texts(
                OutgoingCommandText.findTranslatableSegments("/r 你好", configured)));
        assertTrue(OutgoingCommandText.findTranslatableSegments("/gg 你好", configured).isEmpty());
    }

    @Test
    void configuredCommandsCanSkipLeadingArguments() {
        Map<String, Integer> configured = OutgoingCommandText.parseChatCommands("party:1");
        List<OutgoingCommandText.Segment> segments =
                OutgoingCommandText.findTranslatableSegments("/party chat 你好", configured);

        assertEquals(List.of("你好"), OutgoingCommandText.texts(segments));
        assertEquals("/party chat Hello",
                OutgoingCommandText.rebuild("/party chat 你好", segments, List.of("Hello")));
    }

    @Test
    void chatCommandListToleratesUserFormatting() {
        assertEquals(Map.of("pc", 0, "gc", 0), OutgoingCommandText.parseChatCommands("  /PC ; gc  "));
        assertEquals(Map.of("party", 2), OutgoingCommandText.parseChatCommands("party:2"));
        assertEquals(Map.of("party", 4), OutgoingCommandText.parseChatCommands("party:99"));
        assertEquals(Map.of("say", 0), OutgoingCommandText.parseChatCommands("minecraft:say"));
        assertEquals(Map.of(), OutgoingCommandText.parseChatCommands("   "));
        assertEquals(Map.of(), OutgoingCommandText.parseChatCommands(null));
    }

    @Test
    void settingsOverrideTheBuiltInTable() {
        Map<String, Integer> configured = OutgoingCommandText.parseChatCommands("msg:2");

        assertEquals("/msg a b Hello",
                translate(OutgoingCommandText.findTranslatableSegments("/msg a b 你好", configured),
                        "/msg a b 你好", "Hello"));
    }

    @Test
    void rebuildRefusesMismatchedInput() {
        List<OutgoingCommandText.Segment> segments =
                OutgoingCommandText.findTranslatableSegments("/say 你好");

        assertNull(OutgoingCommandText.rebuild("/say 你好", segments, List.of("a", "b")));
        assertNull(OutgoingCommandText.rebuild("/say 你好", segments, List.of("")));
        assertNull(OutgoingCommandText.rebuild("/say 你好", List.of(), List.of()));
    }
}
