package org.fogofwarlabs.sc4j.scr.client.world;

import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.client.ClientReplayScenario;
import org.fogofwarlabs.sc4j.scr.client.ClientSession;
import org.fogofwarlabs.sc4j.scr.content.ScrResources;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the SC:R game view of a replay against the SC:R game it shows: the active units with their owners, sprite positions, bounds and hit
 * points, unit search, visibility, minerals and gas, and the local player the console sets.
 */
@RequiresInstallation
final class ScrGameViewTest {
    private static final int FRAME = 6000;
    private static final int PLAYER = 0;
    private static final int BLUE_COLOR_INDEX = 1;
    private static final int BLUE = 0x0C48CC;

    @Test
    void view_shows_the_units_players_visibility_and_resources_of_the_game() throws IOException {
        try (ClientSession session = ClientReplayScenario.start(GameTestResources.REPLAY_TVT_BAEKDUDAEGAN, FRAME)) {
            Game game = session.getGame();
            ScrGameView view = new ScrGameView(game);
            Unit worker = ClientReplayScenario.findCompletedUnits(game, PLAYER, ScrUnitIds.TERRAN_SCV).getFirst();
            Sprite sprite = worker.requireSprite();
            Player owner = game.getEngineGame().getPlayer(PLAYER);

            Assertions.assertTrue(view.shows(game));
            Assertions.assertNull(view.getLocalPlayer());
            view.setLocalPlayerIndex(PLAYER);
            Assertions.assertSame(owner, view.getLocalPlayer());
            Assertions.assertEquals(game.getCurrentFrame(), view.getFrame());
            Assertions.assertEquals(game.getEngineGame().getPlayerCount(), view.getPlayers().size());
            Assertions.assertSame(game.getTerrain(), view.getTerrain());
            Assertions.assertTrue(view.getUnits().contains(worker));

            UnitView workerView = view.getUnitView(worker);

            Assertions.assertSame(workerView, view.getUnitView(worker), "one view per unit");
            Assertions.assertSame(owner, workerView.getOwner());
            Assertions.assertEquals((short) sprite.getX(), workerView.getX());
            Assertions.assertEquals((short) sprite.getY(), workerView.getY());
            Assertions.assertTrue(workerView.isPlaced());
            Assertions.assertTrue(workerView.getLeft() < workerView.getX() && workerView.getRight() > workerView.getX());
            Assertions.assertEquals(UnitQueries.getHitPoints(worker), workerView.getHitPoints());
            Assertions.assertEquals(game.getUnitQueries().getMaximumHitPoints(worker), workerView.getMaximumHitPoints());

            List<org.fogofwarlabs.sc4j.engine.entity.Unit> found = new ArrayList<>();

            view.findUnits(workerView.getX(), workerView.getY(), workerView.getX(), workerView.getY(), found);
            Assertions.assertTrue(found.contains(worker));
            Assertions.assertNotNull(view.findUnitAt(workerView.getX(), workerView.getY()));
            Assertions.assertTrue(view.isVisible(owner, workerView.getX(), workerView.getY()));
            Assertions.assertTrue(view.isExplored(owner, workerView.getX(), workerView.getY()));
            Assertions.assertTrue(view.isUnitVisible(owner, worker));
            Assertions.assertEquals(List.of(ScrResources.MINERALS, ScrResources.GAS), view.getResourceKinds());
            Assertions.assertEquals(game.getEngineGame().getEconomy().getAmount(owner, ScrResources.MINERALS),
                                    view.getResourceAmount(owner, ScrResources.MINERALS));

            org.fogofwarlabs.sc4j.scr.game.player.Player slot = game.getPlayerTable().getPlayer(PLAYER);

            Assertions.assertNotNull(slot);
            slot.setColor(BLUE_COLOR_INDEX);
            Assertions.assertEquals(BLUE, view.getTeamColor(owner), "the colour chosen for the game");
        }
    }
}
