package org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.sprite;

import org.fogofwarlabs.sc4j.engine.animation.AnimationContext;
import org.fogofwarlabs.sc4j.engine.animation.AnimationStep;
import org.fogofwarlabs.sc4j.scr.data.format.iscript.IscriptInstruction;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrImageTarget;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrInstruction;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

/**
 * Runs {@code sprul} and {@code spruluselo}: creates a sprite with the heading of the image below the sprite of the
 * image, unless the unit is concealed and the sprite's image is not drawn while cloaked.
 */
public final class ScrCreateSpriteUnderlay extends ScrInstruction {
    /**
     * The frame direction from which {@code spruluselo} places its sprite one elevation lower.
     */
    private static final int LOWERED_SPRITE_FRAME_DIRECTION = 8;

    private final boolean frameElevation;
    private final int spriteType;
    private final int offsetX;
    private final int offsetY;

    /**
     * Creates a {@link ScrCreateSpriteUnderlay} instance.
     *
     * @param decoded        the decoded {@code sprul} or {@code spruluselo} instruction
     * @param frameElevation {@code true} for {@code spruluselo}, whose elevation depends on the frame direction, {@code false} for {@code sprul}
     */
    public ScrCreateSpriteUnderlay(@NotNull IscriptInstruction decoded, boolean frameElevation) {
        super(decoded);

        this.frameElevation = frameElevation;
        this.spriteType     = decoded.getOperands()[0];
        this.offsetX        = decoded.getOperands()[1];
        this.offsetY        = decoded.getOperands()[2];
    }


    /**
     * Runs this instruction for the animated object of the context.
     *
     * @param context the running animation of one object
     * @return how the program continues
     */
    @Override
    public @NotNull AnimationStep execute(@NotNull AnimationContext context) {
        ScrImageTarget target = getTarget(context);

        Unit unit = target.getUnit();
        GameData gameData = target.getGame().requireGameData();
        int effectImage = gameData.getSpriteTypes().getImage(this.spriteType);

        if (unit == null || (unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) == 0 ||
            gameData.getImageTypes().getDrawIfCloaked(effectImage) != 0) {
            SpriteImage image = target.getImage();
            Sprite sprite = target.getSprite();
            int elevation = this.frameElevation
                            ? Math.max(0, sprite.getElevationLevel() - (image.getFrameIndexOffset() >= LOWERED_SPRITE_FRAME_DIRECTION ? 1 : 0))
                            : sprite.getElevationLevel() - 1;

            target.getExecutor().createSpriteEffectAtImage(image,
                                                           sprite,
                                                           this.spriteType,
                                                           this.offsetX, this.offsetY,
                                                           elevation,
                                                           unit,
                                                           true,
                                                           getRemainingInstructions(context));
        }

        return AnimationStep.CONTINUE;
    }
}
