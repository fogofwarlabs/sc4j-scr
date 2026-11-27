package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLineKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.RecordingTranslator;
import org.fogofwarlabs.sc4j.scr.client.TextTranslation;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueScreen;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks the chat log's rectangle in a chat page after the page's style cascade, the log of a page with a text field down to the field, the
 * text field being the screens' framed one, that a log stays the chat's log when the screen it replaced closes, and which entries it asks to
 * translate.
 */
final class LobbyChatLogTest {
    /**
     * The chat page's rectangle in the lobby dialog (control {@code 1} of {@code gluGameLobby}).
     */
    private static final float PAGE_LEFT = 26;
    private static final float PAGE_TOP = 334;
    private static final float PAGE_WIDTH = 340;
    private static final float PAGE_HEIGHT = 140;
    /**
     * A line height of the input.
     */
    private static final float INPUT_LINE_HEIGHT = 9;
    /**
     * The log's margins ({@code .chatArea{margin-top:3px}}, the last {@code .chatArea{margin-bottom:30px}}) and the input
     * form's ({@code form{margin-bottom:4px}}).
     */
    private static final float LOG_TOP_MARGIN = 3;
    private static final float LOG_BOTTOM_MARGIN = 30;
    private static final float FORM_BOTTOM_MARGIN = 4;
    /**
     * The chat page under the room list ({@code GlobalChatPanel}), its control id and the input's; the log's paddings on that page and its
     * gap above the text field.
     */
    private static final int GLOBAL_PAGE_LEFT = 116;
    private static final int GLOBAL_PAGE_TOP = 330;
    private static final int GLOBAL_PAGE_WIDTH = 362;
    private static final int GLOBAL_PAGE_HEIGHT = 144;
    private static final int PAGE_ID = 25;
    private static final int INPUT_ID = 100;
    private static final float GLOBAL_PADDING_LEFT = 7;
    private static final float GLOBAL_PADDING_TOP = 2;
    private static final float FIELD_GAP = 4;
    private static final int SELF_KEY = 1;
    private static final int ZERATUL_KEY = 2;

    private static @NotNull PlayerPresence createPresence(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION, true,
                                                        PlayerStatus.AVAILABLE);
    }

    @Test
    void get_bounds_should_span_the_page_width_without_padding() {
        LobbyChatLog.Bounds bounds = LobbyChatLog.getBounds(PAGE_LEFT, PAGE_TOP, PAGE_WIDTH, PAGE_HEIGHT, INPUT_LINE_HEIGHT);

        Assertions.assertEquals(PAGE_LEFT, bounds.getLeft());
        Assertions.assertEquals(PAGE_TOP + LOG_TOP_MARGIN, bounds.getTop());
        Assertions.assertEquals(PAGE_LEFT + PAGE_WIDTH, bounds.getRight());
        Assertions.assertEquals(PAGE_TOP + PAGE_HEIGHT - FORM_BOTTOM_MARGIN - INPUT_LINE_HEIGHT - LOG_BOTTOM_MARGIN, bounds.getBottom());
    }

    @Test
    void get_bounds_of_a_field_page_should_fill_the_page_down_to_the_field() {
        DialogControl page = ScrDialogControlUtils.createControl(ScrControlType.DIALOG, PAGE_ID,
                                                                 GLOBAL_PAGE_LEFT, GLOBAL_PAGE_TOP, GLOBAL_PAGE_WIDTH, GLOBAL_PAGE_HEIGHT,
                                                                 Set.of(), "", List.of());
        DialogControl field = LobbyChatLog.createFieldInputDefinition(LobbyChatLog.Page.GLOBAL, page, INPUT_ID);
        LobbyChatLog.Bounds bounds = LobbyChatLog.getBounds(LobbyChatLog.Page.GLOBAL, GLOBAL_PAGE_LEFT, GLOBAL_PAGE_TOP, GLOBAL_PAGE_WIDTH,
                                                            GLOBAL_PAGE_HEIGHT);

        Assertions.assertEquals(GLOBAL_PAGE_LEFT + GLOBAL_PADDING_LEFT, bounds.getLeft());
        Assertions.assertEquals(GLOBAL_PAGE_TOP + LOG_TOP_MARGIN + GLOBAL_PADDING_TOP, bounds.getTop());
        Assertions.assertEquals(GLOBAL_PAGE_LEFT + GLOBAL_PAGE_WIDTH, bounds.getRight());
        Assertions.assertEquals(field.getY() - FIELD_GAP, bounds.getBottom());
    }

    @Test
    void create_field_input_definition_should_build_the_screens_framed_text_field() {
        DialogControl page = ScrDialogControlUtils.createControl(ScrControlType.DIALOG, PAGE_ID,
                                                                 GLOBAL_PAGE_LEFT, GLOBAL_PAGE_TOP, GLOBAL_PAGE_WIDTH, GLOBAL_PAGE_HEIGHT,
                                                                 Set.of(), "", List.of());
        DialogControl field = LobbyChatLog.createFieldInputDefinition(LobbyChatLog.Page.GLOBAL, page, INPUT_ID);

        Assertions.assertEquals(ScrControlType.EDITTEXT, ScrDialogControlUtils.getType(field));
        Assertions.assertTrue(ScrDialogControlUtils.hasFlag(field, GlueScreen.FRAMED_FLAG), "The chat input is the screens' framed text field.");
        Assertions.assertEquals(GlueScreen.FRAMED_CONTROL_HEIGHT, field.getHeight());
    }

    @Test
    void stop_listening_should_keep_the_log_of_the_screen_opened_since() {
        RecordingCommunitySession community = new RecordingCommunitySession(
                RecordingCommunitySession.createPresence(SELF_KEY, "Raynor", BeaconLinkGlueScenario.MOD_ID, BeaconLinkGlueScenario.MOD_VERSION,
                                                         true, PlayerStatus.AVAILABLE)
        );
        ChannelChat chat = new ChannelChat(community, Locale.ENGLISH, ChannelChat.SHARED_CHANNEL);
        LobbyChatLog left = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.CHANNEL);
        LobbyChatLog next = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.GLOBAL);

        chat.open();
        left.listen(chat, TextTranslation.OFF, Locale.ENGLISH, null);

        // The next screen opens and listens before the left screen closes.
        next.listen(chat, TextTranslation.OFF, Locale.ENGLISH, null);
        left.stopListening(chat);

        int leftLines = left.getLineCount();
        int nextLines = next.getLineCount();

        chat.submit("hello");

        Assertions.assertEquals(leftLines, left.getLineCount());
        Assertions.assertEquals(nextLines + 1, next.getLineCount(), "The open screen shows the sent line at once.");

        next.stopListening(chat);
        chat.submit("gone");

        Assertions.assertEquals(nextLines + 1, next.getLineCount());
    }

    @Test
    void listen_should_ask_to_translate_only_the_lines_emotes_and_whispers_of_other_players() {
        PlayerPresence self = createPresence(SELF_KEY, "Raynor");
        PlayerPresence zeratul = createPresence(ZERATUL_KEY, "Zeratul");
        RecordingCommunitySession community = new RecordingCommunitySession(self);
        ChannelChat chat = new ChannelChat(community, Locale.ENGLISH, ChannelChat.SHARED_CHANNEL);
        RecordingTranslator translator = new RecordingTranslator();
        LobbyChatLog log = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.CHANNEL);

        community.setChannelSetup(channel -> channel.addMember(zeratul));
        chat.open();
        log.listen(chat, new TextTranslation(translator, true, false), Locale.KOREAN, self.getPlayer());

        RecordingCommunitySession.Channel channel = community.findChannel(ChannelChat.findDefaultChannel(ChannelChat.SHARED_CHANNEL, List.of()));

        Assertions.assertNotNull(channel);

        chat.submit("hello");
        channel.receiveLine(zeratul, ChatLineKind.SAY, "en taro adun");
        channel.receiveLine(zeratul, ChatLineKind.EMOTE, "bows");
        community.receiveWhisper(zeratul, "gg");

        List<TranslationRequest> requests = translator.getRequests();

        Assertions.assertEquals(List.of("en taro adun", zeratul.getTaggedName() + " bows", "gg"),
                                requests.stream().map(TranslationRequest::getText).toList(), "The own line and the notices stay as they are.");
        Assertions.assertEquals(Locale.KOREAN, requests.getFirst().getTarget());
        Assertions.assertEquals(zeratul.getTaggedName().length(), requests.get(1).getProtectedSpans().getFirst().getEnd(),
                                "An emote keeps the name it starts with.");
    }

    @Test
    void listen_should_ask_for_nothing_while_chat_translation_is_off() {
        PlayerPresence zeratul = createPresence(ZERATUL_KEY, "Zeratul");
        RecordingCommunitySession community = new RecordingCommunitySession(createPresence(SELF_KEY, "Raynor"));
        ChannelChat chat = new ChannelChat(community, Locale.ENGLISH, ChannelChat.SHARED_CHANNEL);
        RecordingTranslator translator = new RecordingTranslator();
        LobbyChatLog log = LobbyChatLog.createChatPageLog(LobbyChatLog.Page.CHANNEL);

        chat.open();
        log.listen(chat, new TextTranslation(translator, false, false), Locale.KOREAN, null);
        community.receiveWhisper(zeratul, "gg");

        Assertions.assertEquals(List.of(), translator.getRequests());
    }
}
