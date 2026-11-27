package org.fogofwarlabs.sc4j.scr.client.ui.text;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.client.RecordingTranslator;
import org.fogofwarlabs.sc4j.scr.client.TextTranslation;
import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameContext;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStatTextRegion;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks the {@code stat_txt} string tables: the product name substitution of a parsed table, the committed game-specific button strings and
 * hotkeys with their isolation from the next game, the strings a plugin overrides, unit names included, and the translation of the strings
 * a map changed.
 */
final class StatTextTest {
    private static final int MOVE = 664;
    private static final int CUSTOM = 217;
    /**
     * The installed button string the map-change test rewrites in the committed table.
     */
    private static final int BUILD_SCV = 592;
    private static final @NotNull Locale TARGET = Locale.ENGLISH;

    /**
     * Encodes the committed text offsets and CP949 button text.
     *
     * @return the serialized text table
     */
    private static byte @NotNull [] createCommittedTextTable() {
        Charset korean = Charset.forName("x-windows-949");
        int headerLength = (MOVE + 1) * Short.BYTES;
        byte[] table = new byte[headerLength];

        table[0] = (byte) MOVE;
        table[1] = (byte) (MOVE >> Byte.SIZE);

        int emptyTextOffset = headerLength;
        int customTextOffset = headerLength + 1;
        byte[] button = ("q").getBytes(StandardCharsets.US_ASCII);
        byte[] text = "[Q] 스킬".getBytes(korean);
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        body.write(0);
        body.writeBytes(button);
        body.write(0);
        body.writeBytes(text);
        body.write(0);

        int moveTextOffset = headerLength + body.size();

        body.writeBytes(new byte[]{'z', 1, 4, 'M', 'o', 'v', 'e', ' ', 2, '(', 'Z', ')', 0});

        for (int identifier = 1; identifier <= MOVE; identifier++) {
            int offset = identifier == CUSTOM ? customTextOffset : identifier == MOVE ? moveTextOffset : emptyTextOffset;

            table[identifier * Short.BYTES]     = (byte) offset;
            table[identifier * Short.BYTES + 1] = (byte) (offset >> Byte.SIZE);
        }

        ByteArrayOutputStream all = new ByteArrayOutputStream();

        all.writeBytes(table);
        all.writeBytes(body.toByteArray());

        return all.toByteArray();
    }

    @Test
    void parse_should_substitute_product_name_in_string_tables() throws IOException {
        byte[] json = "[{\"Key\": \"1\", \"Value\": \"Battle.net\\u00AE\"}, {\"Key\": \"2\", \"Value\": \"Log in to Battle.net\\nnow\"}]"
                .getBytes(StandardCharsets.UTF_8);
        StatText text = StatText.parse(json);

        Assertions.assertEquals("Beacon.Link", text.getString(1));
        Assertions.assertEquals("Log in to Beacon.Link\nnow", text.getString(2));
    }

    @RequiresInstallation
    @Test
    void committed_stat_text_table_supplies_button_names_and_hotkeys_for_its_game_only() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> switch (identifier) {
            case MOVE -> "m\\u0000\\u0003M\\u0001ove";
            case CUSTOM -> "Young Chrysalis\\u0000*\\u0000Powerups";
            default -> "";
        });
        HotkeyProfile profile = new HotkeyProfile(text, HotkeyProfile.DEVICE, Map.of());

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));

            game.getGameContext().setStatTextTable(createCommittedTextTable());

            Assertions.assertEquals("q\0[Q] 스킬", text.getString(new ScrGameView(game), CUSTOM), "Type 0: the text from the pointer plus two.");
            Assertions.assertEquals("q\0[Q] 스킬", profile.getString(new ScrGameView(game), CUSTOM), "An id outside the profile table is the plain string.");
            Assertions.assertEquals('q', profile.getHotkey(new ScrGameView(game), CUSTOM), "The map's key.");
            Assertions.assertEquals("z\u0001\u0004Move \u0002(Z)", profile.getString(new ScrGameView(game), MOVE), "A profile id is the plain string.");
            Assertions.assertEquals('z', profile.getHotkey(new ScrGameView(game), MOVE), "A profile id takes the map's key.");

            Game next = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));

            Assertions.assertEquals("Young Chrysalis\0*\0Powerups", text.getString(new ScrGameView(next), CUSTOM), "A new game reads the installed strings.");
            Assertions.assertEquals('Y', profile.getHotkey(new ScrGameView(next), CUSTOM));
            Assertions.assertEquals("m\0Move [\u0003M\u0001]", profile.getString(new ScrGameView(next), MOVE), "A new game applies the profile.");
            Assertions.assertEquals('m', profile.getHotkey(new ScrGameView(next), MOVE));
        }
    }

    @Test
    void plugin_overrides_win_over_the_committed_table() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> identifier == CUSTOM ? "Installed" : "");
        Game game = Game.create(null, new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
        ScrGameView view = new ScrGameView(game);

        game.getGameContext().setStatTextTable(createCommittedTextTable());
        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, "Script");
        game.getInterfaceLink().getStatTextOverrides().set(MOVE + 1, "Beyond the table");

        Assertions.assertEquals("Script", text.getString(view, CUSTOM));
        Assertions.assertEquals("Beyond the table", text.getString(view, MOVE + 1));

        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, null);

        Assertions.assertEquals("q\0[Q] 스킬", text.getString(view, CUSTOM), "The committed table again.");
    }

    @RequiresInstallation
    @Test
    void strings_a_map_changed_show_their_translation_with_the_button_prefix_kept() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> "");
        HotkeyProfile profile = new HotkeyProfile(text, HotkeyProfile.DEVICE, Map.of());
        String changed = "s\u0001\u0003생산 \u0004[S]";
        byte[] changedBytes = changed.getBytes(Charset.forName("x-windows-949"));

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            byte[] table = data.readInstalledFile(ClassicStatTextRegion.FILE);
            byte[] installed = GameContext.readStatTextBytes(table, BUILD_SCV, 0);
            int start = LittleEndianUtils.readUnsigned16(table, BUILD_SCV * Short.BYTES);

            Assertions.assertNotNull(installed);
            Assertions.assertTrue(changedBytes.length <= installed.length, "The changed string fits in place of the installed one.");

            System.arraycopy(changedBytes, 0, table, start, changedBytes.length);
            table[start + changedBytes.length] = 0;

            Game game = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
            ScrGameView view = new ScrGameView(game);
            RecordingTranslator translator = new RecordingTranslator();

            game.getGameContext().setStatTextTable(table);

            String move = text.getString(view, MOVE);

            view.setTextTranslation(new TextTranslation(translator, false, true), TARGET);

            Assertions.assertEquals(changed, text.getString(view, BUILD_SCV), "The map's string until its translation came.");
            Assertions.assertEquals(move, text.getString(view, MOVE), "An unchanged string stays as it is.");
            Assertions.assertEquals(List.of("\u0003생산 \u0004[S]"), translator.getRequests().stream().map(TranslationRequest::getText).toList(),
                                    "The text after the hotkey and the type byte is asked for.");

            translator.answer(0, "\u0003Train \u0004[S]");

            Assertions.assertEquals("s\u0001\u0003Train \u0004[S]", text.getString(view, BUILD_SCV));
            Assertions.assertEquals('s', profile.getHotkey(view, BUILD_SCV), "The hotkey stays.");
            Assertions.assertEquals(move, text.getString(view, MOVE));
            Assertions.assertEquals(1, translator.getRequests().size());
        }
    }

    @Test
    void plugin_overrides_that_differ_from_the_installed_string_show_their_translation() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> identifier == CUSTOM ? "Installed" : "");
        Game game = Game.create(null, new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
        ScrGameView view = new ScrGameView(game);
        RecordingTranslator translator = new RecordingTranslator();

        view.setTextTranslation(new TextTranslation(translator, false, true), TARGET);
        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, "Installed");

        Assertions.assertEquals("Installed", text.getString(view, CUSTOM));
        Assertions.assertEquals(List.of(), translator.getRequests(), "An override that equals the installed string is not asked for.");

        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, "Bouclier du roi");

        Assertions.assertEquals("Bouclier du roi", text.getString(view, CUSTOM));

        translator.answer(0, "King's shield");

        Assertions.assertEquals("King's shield", text.getString(view, CUSTOM));
    }

    @RequiresInstallation
    @Test
    void an_overridden_unit_name_wins_over_the_installed_name() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> identifier == 1 ? "Terran Marine" : "");

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
            ScrGameView view = new ScrGameView(game);

            Assertions.assertEquals("Terran Marine", text.getUnitName(view, 0));

            game.getInterfaceLink().getStatTextOverrides().set(1, "Space Marine");

            Assertions.assertNull(game.getUnitDisplayName(0));
            Assertions.assertEquals("Space Marine", text.getUnitName(view, 0));
        }
    }
}
