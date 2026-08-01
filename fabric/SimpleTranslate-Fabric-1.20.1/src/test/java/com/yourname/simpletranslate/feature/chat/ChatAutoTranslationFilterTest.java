package com.yourname.simpletranslate.feature.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAutoTranslationFilterTest {
    @Test
    void fullwidthSystemProseIsEligibleForAutoTranslation() {
        String message = "[ａｖＳＹＳ] ／／ＣＲＩＴＩＣＡＬ ＬＩＦＥ ＳＵＰＰＯＲＴ ＥＲＲＯＲ！／／";

        assertEquals("[avSYS] //CRITICAL LIFE SUPPORT ERROR!//",
                ChatAutoTranslationFilter.candidateBodyForTest(message));
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate(message));
    }

    @Test
    void fullwidthShortPlayerChatterRemainsFiltered() {
        assertFalse(ChatAutoTranslationFilter.shouldAutoTranslate("<Ｐｌａｙｅｒ> ｇｇ"));
    }

    @Test
    void multiWordSystemLabelsAreNotMistakenForPlayerPrefixes() {
        for (String message : new String[]{
                "Master Mode: [?]: ✗",
                "Death Counter: [?]: ✗",
                "Give Transparent Armor: [?]: ✓",
                "Quest Progress: [3/10]"
        }) {
            assertEquals(message, ChatAutoTranslationFilter.candidateBodyForTest(message));
            assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate(message));
        }
    }

    @Test
    void ambiguousSymbolOnlyColonValueFailsOpenAsSystemText() {
        assertEquals("Status: ✓",
                ChatAutoTranslationFilter.candidateBodyForTest("Status: ✓"));
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate("Status: ✓"));
    }

    @Test
    void ordinaryPlayerChatStillUsesOnlyItsMessageBody() {
        assertEquals("gg", ChatAutoTranslationFilter.candidateBodyForTest("Player: gg"));
        assertFalse(ChatAutoTranslationFilter.shouldAutoTranslate("Player: gg"));
        assertEquals("Please help at spawn.",
                ChatAutoTranslationFilter.candidateBodyForTest("Player: Please help at spawn."));
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate(
                "Player: Please help at spawn."));
    }
}
