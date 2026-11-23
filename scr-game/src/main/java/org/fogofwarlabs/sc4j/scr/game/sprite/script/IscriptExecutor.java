package org.fogofwarlabs.sc4j.scr.game.sprite.script;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationRunner;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationState;
import org.fogofwarlabs.sc4j.engine.animation.system.InstructionBudgetExhaustedException;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.UnitSounds;
import org.fogofwarlabs.sc4j.scr.game.sprite.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrImageTarget;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction.ScrPreviewSupport;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitCreationContext;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.fogofwarlabs.sc4j.scr.game.visibility.UnitCloaking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Runs the iscript interpreter of sprite images: the per-frame image scripts, animation requests, image and sprite creation, the sprite effect
 * updates and the image replacements of units.
 * <p>
 * An image script is the game's iscript program ({@link GameData#getIscriptProgram}) of SC:R instructions, run by the engine
 * {@link AnimationRunner} of the image's pool on the pool's animation state. A nonzero wait decrements and returns on that invocation, also when it
 * becomes zero. Animation requests use the image request gate. The bounded image preview accepts only the checked operations.
 */
public final class IscriptExecutor {
    /**
     * The iscript {@code Init} animation type.
     */
    public static final int ANIMATION_INIT = 0;
    /**
     * The iscript {@code Death} animation type.
     */
    public static final int ANIMATION_DEATH = 1;
    /**
     * The iscript {@code GndAttkInit} animation type.
     */
    public static final int ANIMATION_GROUND_ATTACK_INIT = 2;
    /**
     * The iscript {@code AirAttkInit} animation type.
     */
    public static final int ANIMATION_AIR_ATTACK_INIT = 3;
    /**
     * The iscript {@code Unused1} animation type.
     */
    public static final int ANIMATION_UNUSED_1 = 4;
    /**
     * The iscript {@code GndAttkRpt} animation type.
     */
    public static final int ANIMATION_GROUND_ATTACK_REPEAT = 5;
    /**
     * The iscript {@code AirAttkRpt} animation type.
     */
    public static final int ANIMATION_AIR_ATTACK_REPEAT = 6;
    /**
     * The iscript {@code CastSpell} animation type.
     */
    public static final int ANIMATION_CAST_SPELL = 7;
    /**
     * The iscript {@code GndAttkToIdle} animation type.
     */
    public static final int ANIMATION_GROUND_ATTACK_TO_IDLE = 8;
    /**
     * The iscript {@code AirAttkToIdle} animation type.
     */
    public static final int ANIMATION_AIR_ATTACK_TO_IDLE = 9;
    /**
     * The iscript {@code Unused2} animation type.
     */
    public static final int ANIMATION_UNUSED_2 = 10;
    /**
     * The iscript {@code Walking} animation type.
     */
    public static final int ANIMATION_WALKING = 11;
    /**
     * The iscript {@code WalkingToIdle} animation type.
     */
    public static final int ANIMATION_WALKING_TO_IDLE = 12;
    /**
     * The iscript {@code SpecialState1} animation type.
     */
    public static final int ANIMATION_SPECIAL_STATE_1 = 13;
    /**
     * The iscript {@code SpecialState2} animation type.
     */
    public static final int ANIMATION_SPECIAL_STATE_2 = 14;
    /**
     * The iscript {@code AlmostBuilt} animation type.
     */
    public static final int ANIMATION_ALMOST_BUILT = 15;
    /**
     * The iscript {@code Built} animation type.
     */
    public static final int ANIMATION_BUILT = 16;
    /**
     * The iscript {@code Landing} animation type.
     */
    public static final int ANIMATION_LANDING = 17;
    /**
     * The iscript {@code LiftOff} animation type.
     */
    public static final int ANIMATION_LIFT_OFF = 18;
    /**
     * The iscript {@code IsWorking} animation type.
     */
    public static final int ANIMATION_IS_WORKING = 19;
    /**
     * The iscript {@code WorkingToIdle} animation type.
     */
    public static final int ANIMATION_WORKING_TO_IDLE = 20;
    /**
     * The iscript {@code WarpIn} animation type.
     */
    public static final int ANIMATION_WARP_IN = 21;
    /**
     * The iscript {@code Unused3} animation type.
     */
    public static final int ANIMATION_UNUSED_3 = 22;
    /**
     * The iscript {@code StarEditInit} animation type.
     */
    public static final int ANIMATION_STAR_EDIT_INIT = 23;
    /**
     * The iscript {@code Disable} animation type.
     */
    public static final int ANIMATION_DISABLE = 24;
    /**
     * The iscript {@code Burrow} animation type.
     */
    public static final int ANIMATION_BURROW = 25;
    /**
     * The iscript {@code UnBurrow} animation type.
     */
    public static final int ANIMATION_UN_BURROW = 26;
    /**
     * The iscript {@code Enable} animation type.
     */
    public static final int ANIMATION_ENABLE = 27;
    /**
     * The {@code force} argument of an animation request ({@link #runSpriteAnimation}) that also runs while the sprite's nonbreaking flag is set.
     */
    public static final int FORCE_ANIMATION = 1;
    /**
     * The construction mode of {@link #initializeConstructionImage} that prefers the unit type's nonzero construction image.
     */
    public static final int CONSTRUCTION_IMAGE_MODE = 1;
    /**
     * The construction mode of {@link #initializeConstructionImage} that takes the image of the sprite type instead of the construction image.
     */
    public static final int COMPLETED_IMAGE_MODE = 0;

    /**
     * The fraction bits of iscript movement speed, used by the {@code move} instruction.
     */
    public static final int MOVEMENT_FRACTION_BITS = 8;
    /**
     * The insertion order of an image created above its reference image.
     */
    public static final int ORDER_ABOVE = 1;
    /**
     * The insertion order of an image created below its reference image.
     */
    public static final int ORDER_BELOW = -1;

    /**
     * The number of nested bounded invocations the executor holds before it grows.
     */
    private static final int INITIAL_EXECUTION_DEPTH = 8;
    /**
     * The random source the animation runners are created with; the iscript instructions draw with their own native sources.
     */
    private static final int RUNNER_RANDOM_SOURCE = 3;
    /**
     * The mask retaining an unsigned byte from interpreter operands and image state.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The divisor of a placement box size that gives the extent of the box on each side of the sprite center.
     */
    private static final int PLACEMENT_BOX_CENTER_DIVISOR = 2;
    /**
     * The ordinary image drawing-function identifier.
     */
    private static final int ORDINARY_IMAGE_MODIFIER = 0;
    /**
     * The {@code force} argument of an animation request that respects the sprite's visibility flag.
     */
    private static final int NOT_FORCED = 0;
    /**
     * The owner byte of the sprite effects that the iscript sprite opcodes create.
     */
    private static final int SCRIPT_SPRITE_EFFECT_OWNER = 0;
    /**
     * The owner byte of the hidden startup sprite effect.
     */
    private static final int STARTUP_SPRITE_EFFECT_OWNER = 0;

    /**
     * The shift from a heading byte to its frame direction.
     */
    private static final int FRAME_DIRECTION_SHIFT = 3;
    /**
     * The half frame direction added to a heading before the shift, which rounds to the nearest frame direction.
     */
    private static final int FRAME_DIRECTION_ROUNDING = 4;
    /**
     * The number of frame directions; directions above {@link #LAST_UNFLIPPED_FRAME_DIRECTION} use flipped frames.
     */
    private static final int FRAME_DIRECTION_COUNT = 32;
    /**
     * The last frame direction drawn without flipping.
     */
    private static final int LAST_UNFLIPPED_FRAME_DIRECTION = 16;

    /**
     * The bit position of {@link SpriteImage#FLAG_HAS_DIRECTIONAL_FRAMES}, set from the {@code images.dat} Graphic Turns.
     */
    private static final int DIRECTIONAL_FRAMES_FLAG_SHIFT = 3;
    /**
     * The bit position of {@link SpriteImage#FLAG_USES_FULL_ISCRIPT}, set from the {@code images.dat} Iscript flag.
     */
    private static final int FULL_ISCRIPT_FLAG_SHIFT = 4;
    /**
     * The bit position of {@link SpriteImage#FLAG_CLICKABLE}, set from the {@code images.dat} Clickable column.
     */
    private static final int CLICKABLE_FLAG_SHIFT = 5;
    /**
     * The mask retaining the lowest bit of an {@code images.dat} flag column or of the {@code setflipstate} operand.
     */
    private static final int FLAG_BIT_MASK = 1;
    /**
     * The mask retaining the high word of a modifier data DWORD or of a packed coordinate pair.
     */
    private static final int HIGH_WORD_MASK = 0xFFFF0000;
    /**
     * The mask retaining the low word of a modifier data DWORD, of a packed coordinate pair or of an unsigned word value.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * One step of the warp flash delay, the second byte of the modifier data.
     */
    private static final int WARP_FLASH_DELAY_STEP = 0x100;
    /**
     * The warp flash delay byte of the modifier data.
     */
    private static final int WARP_FLASH_DELAY_MASK = 0xFF00;
    /**
     * The warp flash delay stored after each brightness step.
     */
    private static final int WARP_FLASH_DELAY_RELOAD = 0x0200;
    /**
     * The last warp flash brightness; the flash ends with the Death animation after it.
     */
    private static final int WARP_FLASH_LAST_DATA = 63;

    /**
     * The first sprite of the range whose sprite effects update their placement box instead of the explored flags.
     */
    private static final int PLACEMENT_BOX_FIRST_SPRITE = 130;
    /**
     * The last offset of the placement box sprite range from {@link #PLACEMENT_BOX_FIRST_SPRITE}.
     */
    private static final int PLACEMENT_BOX_LAST_SPRITE_OFFSET = 255;
    /**
     * The sprite id from which sprite effects update their placement box instead of the explored flags again.
     */
    private static final int EXPLORED_SPRITE_LIMIT = 481;

    /**
     * The iscript identifier of the building warp texture.
     */
    private static final int WARP_TEXTURE_SCRIPT = 193;
    /**
     * The nesting limit of image creation inside image Init scripts.
     */
    private static final int MAXIMUM_CREATION_DEPTH = 64;

    /**
     * The number of carried-image ranges {@link #removeCarryImages(Unit)} destroys, one pass each.
     */
    private static final int CARRYING_RANGE_COUNT = 3;
    /**
     * The first pass of {@link #removeCarryImages(Unit)}, over the images after the carried powerup images.
     */
    private static final int CARRIED_EXTRA_RANGE = 0;
    /**
     * The second pass of {@link #removeCarryImages(Unit)}, over the carried powerup and resource images.
     */
    private static final int CARRIED_ITEM_RANGE = 1;
    /**
     * The first of the images after the carried powerup images, which the first pass destroys.
     */
    private static final int CARRIED_EXTRA_FIRST_IMAGE = 405;
    /**
     * The last of the images after the carried powerup images.
     */
    private static final int CARRIED_EXTRA_LAST_IMAGE = 418;

    /**
     * Applies a heading byte to the images of a sprite in forward list order.
     * <p>
     * Rounding to a frame direction happens after adding {@code 4}, so headings {@code 252..255} produce flipped frame zero.
     *
     * @param sprite    the owning sprite
     * @param direction the heading byte
     * @throws UnsupportedOperationException if an image uses the special overlay offset, which needs the game
     */
    public static void applySpriteDirection(@NotNull Sprite sprite, int direction) {
        applySpriteDirection(null, sprite, direction);
    }

    /**
     * Updates the directional frames and the carried-image offsets of a sprite's images in list order.
     *
     * @param game      the game supplying the special overlay offsets, or {@code null} without special offsets
     * @param sprite    the owning sprite
     * @param direction the heading byte
     * @throws UnsupportedOperationException if an image uses the special overlay offset and {@code game} is {@code null}
     */
    public static void applySpriteDirection(@Nullable Game game, @NotNull Sprite sprite, int direction) {
        int frameDirection = ((direction & BYTE_MASK) + FRAME_DIRECTION_ROUNDING) >>> FRAME_DIRECTION_SHIFT;

        for (SpriteImage image : sprite.getImages()) {
            if ((image.getFlags() & SpriteImage.FLAG_USES_SPECIAL_OFFSET) != 0) {
                if (game == null) {
                    throw Exceptions.newUnsupportedOperationException("Special image offsets require simulation resources.");
                }

                game.getIscriptExecutor().updateSpecialOffset(image);
            }

            if ((image.getFlags() & SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES) == 0) {
                continue;
            }

            boolean flipped = frameDirection > LAST_UNFLIPPED_FRAME_DIRECTION;
            int frameOffset = flipped ? FRAME_DIRECTION_COUNT - frameDirection : frameDirection;

            if (image.getFrameIndexOffset() == frameOffset && ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) == flipped) {
                continue;
            }

            image.setFrameIndexOffset(frameOffset);
            image.setFlags((image.getFlags() & ~SpriteImage.FLAG_HORIZONTALLY_FLIPPED) |
                           (flipped ? SpriteImage.FLAG_HORIZONTALLY_FLIPPED : 0) | SpriteImage.FLAG_REDRAW);

            if (image.getModifier() == SpriteImage.MODIFIER_WARP_FLASH) {
                image.setModifierData1((image.getModifierData1() & HIGH_WORD_MASK) | SpriteImage.WARP_FLASH_START_DATA);
            }
        }
    }

    /**
     * Tests the animation request gate: a repeated Death, an animation above Death without the full-iscript flag and a repeated Walking or IsWorking
     * are ignored.
     *
     * @param image         the image receiving the request
     * @param animationType the requested animation byte
     * @return {@code true} if the request is ignored, {@code false} otherwise
     */
    private static boolean isAnimationRequestIgnored(@NotNull SpriteImage image, int animationType) {
        int currentAnimation = image.getAnimationType();

        return animationType == ANIMATION_DEATH && currentAnimation == ANIMATION_DEATH ||
               (image.getFlags() & SpriteImage.FLAG_USES_FULL_ISCRIPT) == 0 && animationType > ANIMATION_DEATH ||
               animationType == currentAnimation && (animationType == ANIMATION_WALKING || animationType == ANIMATION_IS_WORKING);
    }

    /**
     * Checks the instruction limit of a bounded invocation.
     *
     * @param instructionLimit the instruction limit
     * @throws IllegalArgumentException if {@code instructionLimit} is not positive
     */
    private static void checkInstructionLimit(int instructionLimit) {
        if (instructionLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("The instruction limit must be positive.");
        }
    }


    private final @NotNull Game game;
    private final @NotNull ScrImageTarget target;

    private @NotNull IscriptExecution @NotNull [] executions;
    private int executionDepth;

    /**
     * Creates a {@link IscriptExecutor} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public IscriptExecutor(@NotNull Game game) {
        this.game   = game;
        this.target = new ScrImageTarget(game);

        this.executions     = new IscriptExecution[INITIAL_EXECUTION_DEPTH];
        this.executionDepth = 0;

        for (int depth = 0; depth < INITIAL_EXECUTION_DEPTH; depth++) {
            this.executions[depth] = new IscriptExecution(0);
        }
    }

    /**
     * Takes the bounded invocation of the next nesting level with a full budget; the caller releases it when the invocation ends
     * ({@link #releaseExecution}).
     *
     * @param instructionLimit the instruction budget of the invocation
     * @return the invocation
     */
    private @NotNull IscriptExecution acquireExecution(int instructionLimit) {
        int depth = this.executionDepth;

        if (depth == this.executions.length) {
            this.executions = Arrays.copyOf(this.executions, depth * 2);

            for (int created = depth; created < this.executions.length; created++) {
                this.executions[created] = new IscriptExecution(0);
            }
        }

        IscriptExecution execution = this.executions[depth];

        execution.reset(instructionLimit);
        this.executionDepth = depth + 1;

        return execution;
    }

    /**
     * Releases the bounded invocation taken last.
     */
    private void releaseExecution() {
        this.executionDepth--;
    }

    /**
     * Reads the GRP of the image's type.
     *
     * @param image the image
     * @return the GRP of the image's type
     * @throws IllegalStateException if the GRP cannot be read
     * @throws NullPointerException  if the world was built without game data
     */
    public @NotNull GrpFile getGrpFile(@NotNull SpriteImage image) {
        try {
            return this.game.requireGameData().getGrpFile(image.getImageTypeId());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Failed to load the GRP for image {0}", image.getImageTypeId());
        }
    }

    /**
     * Reads an overlay offset of the image's current frame, mirrored horizontally for a flipped image.
     *
     * @param image       the image
     * @param overlayType the overlay kind
     * @param position    the overlay position index
     * @return the packed offset: the horizontal offset in the low word, the vertical offset in the high word
     * @throws IllegalStateException if the overlay coordinates cannot be read
     * @throws NullPointerException  if the world was built without game data
     */
    public int getImageOverlayOffset(@NotNull SpriteImage image, int overlayType, int position) {
        try {
            int offset = this.game.requireGameData().getImageOverlayOffset(image.getImageTypeId(), overlayType, image.getDrawnFrameIndex(), position);
            int horizontal = (short) offset;

            if ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
                horizontal = -horizontal;
            }

            return horizontal & WORD_MASK | offset & HIGH_WORD_MASK;
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Cannot load image overlay coordinates.");
        }
    }


    /**
     * Processes one invocation of the image's iscript interpreter.
     *
     * @param image  the image
     * @param sprite the sprite the image belongs to
     * @param unit   the unit context, or {@code null} for unit-free animations
     * @return {@code true} if the image is still alive, {@code false} otherwise
     * @throws IllegalStateException         if an instruction cannot be decoded or run, or an ended image has no owning pool
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if an image creation opcode needs a bounded invocation, the opcode is {@code uflunstable} or player upgrades are missing
     */
    public boolean execute(@NotNull SpriteImage image, @NotNull Sprite sprite, @Nullable Unit unit) {
        return this.execute(null, image, sprite, unit);
    }

    /**
     * Runs the image's script on the animation runner of its pool: counts a pending wait down, or executes instructions up to the next wait or end.
     * <p>
     * The image runs the game's iscript program; its pool is bound to the program first ({@link SpriteImagePool#bindIscript}). A bounded
     * invocation takes every instruction, a byte above the last opcode included, from its budget, stores the offset of each instruction before
     * it runs and runs only the opcodes that support it ({@link ScrPreviewSupport}); an unbounded invocation stores the offset only at a wait or
     * end. The image of this run is the current image of the game's {@link ScrImageTarget} while it runs.
     *
     * @param preview the bounded invocation, or {@code null} for an unbounded frame invocation
     * @param image   the image
     * @param sprite  the sprite the image belongs to
     * @param unit    the unit context, or {@code null}
     * @return {@code true} if the image is still alive, {@code false} otherwise
     * @throws IllegalStateException         if {@code preview} runs out of instructions, an instruction cannot be decoded or run, or an ended image has no owning pool
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if an opcode is not supported in this invocation, the opcode is {@code uflunstable} or player upgrades are missing
     */
    private boolean execute(@Nullable IscriptExecution preview, @NotNull SpriteImage image, @NotNull Sprite sprite, @Nullable Unit unit) {
        SpriteImagePool pool = image.getPool();
        AnimationRunner runner = this.getAnimationRunner(pool);
        int index = image.getIndex();

        // A pending wait only counts down: the runner runs no instruction, so the image needs no program binding and no target.
        if (runner.countDown(image)) {
            return true;
        }

        AnimationState animations = pool.getAnimationState();
        GameData gameData = this.game.requireGameData();

        pool.bindIscript(gameData.getIscriptProgram(), gameData.getIscriptOffsets());

        // An image always runs its script; the engine running flag only marks the end of the last one.
        if (!animations.isRunning(index)) {
            animations.setRunning(index, true);
        }

        this.target.enter(preview, image, sprite, unit);

        try {
            return preview == null ? runner.update(image) : runner.update(image, preview.getBudget());
        } catch (InstructionBudgetExhaustedException exception) {
            throw Exceptions.newIllegalStateException("Image preview instruction limit reached for image {0} at offset {1}",
                                                      image.getImageTypeId(),
                                                      gameData.getIscriptOffsets().getOffset(exception.getPosition()));
        } finally {
            this.target.leave();
        }
    }

    /**
     * Returns the animation runner of an image pool, creating it with the game's target at the first use.
     *
     * @param pool the image pool
     * @return the runner of the pool's animation state
     */
    private @NotNull AnimationRunner getAnimationRunner(@NotNull SpriteImagePool pool) {
        AnimationRunner runner = pool.getAnimationRunner();

        if (runner == null) {
            runner = new AnimationRunner(pool.getAnimationState(), this.target, this.target,
                                         this.game.getRandom(), RUNNER_RANDOM_SOURCE, Integer.MAX_VALUE);
            pool.setAnimationRunner(runner);
        }

        return runner;
    }

    /**
     * Invokes the checked image-only instruction subset for manual inspection.
     * <p>
     * This does not schedule a game frame. Unsupported operations and instruction-limit exhaustion stop at the offending offset; changes from
     * preceding instructions remain available for inspection.
     *
     * @param image            the inspected image
     * @param sprite           the image's sprite
     * @param instructionLimit the maximum instructions in this invocation
     * @return {@code true} if the image remains alive, {@code false} otherwise
     * @throws IllegalArgumentException      if the limit is not positive
     * @throws UnsupportedOperationException if an operation is outside the checked subset
     * @throws IllegalStateException         if the limit runs out or instruction decoding fails
     */
    public boolean executeImagePreview(@NotNull SpriteImage image, @NotNull Sprite sprite, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            return this.execute(preview, image, sprite, null);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Invokes an isolated sprite's image list in forward list order.
     * <p>
     * The next existing image is taken before each invocation. Newly created images run Init immediately but are not revisited during that traversal.
     * This is a manual invocation boundary, not a world frame scheduler. The cloak and warp-in flash draw functions are updated before the script of
     * their image runs.
     *
     * @param sprite           the inspected sprite
     * @param instructionLimit the shared instruction limit, including nested image Init
     * @return {@code true} if the sprite retains an image, {@code false} otherwise
     * @throws IllegalArgumentException      if the limit is not positive
     * @throws UnsupportedOperationException if an image update or opcode is unsupported
     * @throws IllegalStateException         if the limit runs out, instruction decoding fails or the sprite loses its last image without an owning sprite pool
     */
    public boolean executeSpritePreview(@NotNull Sprite sprite, int instructionLimit) {
        return this.executeSpriteImages(sprite, null, instructionLimit);
    }

    /**
     * Invokes the image updates and scripts of a sprite's images in list order.
     * <p>
     * The caller supplies the scheduling boundary and the current unit context. The next image is taken before each invocation, also when the current
     * image ends.
     *
     * @param sprite           the allocated sprite
     * @param unit             the active unit context, or {@code null}
     * @param instructionLimit the shared limit for this invocation and nested Init calls
     * @return {@code true} if any image remains attached, {@code false} otherwise
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the limit runs out, instruction decoding fails or the sprite loses its last image without an owning sprite pool
     */
    public boolean executeSprite(@NotNull Sprite sprite, @Nullable Unit unit, int instructionLimit) {
        return this.executeSpriteImages(sprite, unit, instructionLimit);
    }

    /**
     * Runs the image updates and scripts of a sprite's images in list order, taking the next image before each one.
     *
     * @param sprite           the sprite
     * @param unit             the unit context, or {@code null}
     * @param instructionLimit the shared instruction limit
     * @return {@code true} if any image remains attached, {@code false} otherwise
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the sprite loses its last image without an owning sprite pool
     */
    private boolean executeSpriteImages(@NotNull Sprite sprite, @Nullable Unit unit, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        SpriteImage image = sprite.getImages().isEmpty() ? null : sprite.getImages().getFirst();
        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            return this.executeSpriteImages(preview, sprite, unit, image);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Runs the image updates and scripts of a sprite's images in list order within one bounded invocation.
     *
     * @param preview the bounded invocation
     * @param sprite  the sprite
     * @param unit    the unit context, or {@code null}
     * @param image   the first image, or {@code null} for an empty sprite
     * @return {@code true} if any image remains attached, {@code false} otherwise
     * @throws IllegalStateException if the sprite loses its last image without an owning sprite pool
     */
    private boolean executeSpriteImages(@NotNull IscriptExecution preview, @NotNull Sprite sprite, @Nullable Unit unit, @Nullable SpriteImage image) {
        while (image != null) {
            int imageIndex = sprite.getImages().indexOf(image);
            SpriteImage nextImage = imageIndex + 1 < sprite.getImages().size() ? sprite.getImages().get(imageIndex + 1) : null;

            // Update the cloak and warp-in draw functions here.
            if (image.getModifier() >= SpriteImage.MODIFIER_FIRST_CLOAK && image.getModifier() <= SpriteImage.MODIFIER_LAST_CLOAK) {
                UnitCloaking.updateImageCloak(image, unit);
            }

            if (image.getModifier() == SpriteImage.MODIFIER_WARP_FLASH) {
                int modifierData = image.getModifierData1();
                int delay = modifierData >>> Byte.SIZE & BYTE_MASK;

                if (delay != 0) {
                    image.setModifierData1(modifierData - WARP_FLASH_DELAY_STEP);
                } else {
                    image.setModifierData1(modifierData & ~WARP_FLASH_DELAY_MASK | WARP_FLASH_DELAY_RELOAD);

                    if ((modifierData & BYTE_MASK) < WARP_FLASH_LAST_DATA) {
                        image.setModifierData1(image.getModifierData1() + 1);
                    } else {
                        this.runAnimation(preview, image, sprite, unit, image.getScriptIdentifier(), ANIMATION_DEATH);

                        if (unit != null) {
                            unit.setOrderSignal(unit.getOrderSignal() | Unit.ORDER_SIGNAL_ACTION_DONE);
                        } else {
                            this.game.getProjectilePhase().signal(Unit.ORDER_SIGNAL_ACTION_DONE);
                        }
                    }

                    image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
                }
            }

            this.execute(preview, image, sprite, unit);

            if (sprite.getImages().isEmpty()) {
                SpritePool pool = this.game.getSpriteSystem().getSpritePool();

                if (pool == null) {
                    throw Exceptions.newIllegalStateException("The native sprite has no owning pool.");
                }

                if (!pool.recycleEmptySprite(sprite, this.game.getMap().getHeight())) {
                    return false;
                }
            }

            image = nextImage;
        }

        return !sprite.getImages().isEmpty();
    }

    /**
     * Requests an animation on every image of a sprite, reading the next image after each call.
     * <p>
     * After a call ends and recycles an image, dispatch continues over the free image list. Entries whose request gate ignores the animation are
     * passed over; a request that would execute freed storage fails explicitly.
     *
     * @param sprite           the allocated sprite
     * @param unit             the current unit context, or {@code null}
     * @param animationType    the requested animation byte
     * @param force            nonzero to run the animation also while the sprite's nonbreaking flag ({@link Sprite#FLAG_ISCRIPT_NO_BREAK}, {@code 0x80}) is set
     * @param instructionLimit the shared instruction limit including child Init calls
     * @throws IllegalArgumentException      if the limit is not positive
     * @throws UnsupportedOperationException if the request would run on a freed image
     */
    public void runSpriteAnimation(@NotNull Sprite sprite, @Nullable Unit unit, int animationType, int force, int instructionLimit) {
        if (instructionLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("Native animation dispatch requires sprite storage and a positive instruction limit.");
        }

        SpriteImage image = sprite.getImages().isEmpty() ? null : sprite.getImages().getFirst();
        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            this.runSpriteAnimation(preview, sprite, unit, animationType, force, image);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Requests an animation on every image of a sprite within one bounded invocation, reading the next image after each call.
     *
     * @param preview       the bounded invocation
     * @param sprite        the allocated sprite
     * @param unit          the current unit context, or {@code null}
     * @param animationType the requested animation byte
     * @param force         nonzero to run the animation also while the sprite's nonbreaking flag is set
     * @param image         the first image, or {@code null} for an empty sprite
     * @throws UnsupportedOperationException if the request would run on a freed image
     */
    private void runSpriteAnimation(@NotNull IscriptExecution preview, @NotNull Sprite sprite, @Nullable Unit unit, int animationType, int force,
                                    @Nullable SpriteImage image) {
        while (image != null) {
            if (force != 0 || (byte) sprite.getFlags() >= 0) {
                this.runAnimation(preview, image, sprite, unit, image.getScriptIdentifier(), animationType);
            }

            int imageIndex = sprite.getImages().indexOf(image);

            if (imageIndex < 0) {
                this.passFreedImages(sprite, image, animationType, force);

                return;
            }

            image = imageIndex + 1 < sprite.getImages().size() ? sprite.getImages().get(imageIndex + 1) : null;
        }
    }

    /**
     * Continues the animation dispatch over the free image list after an image the call ended.
     *
     * @param sprite        the dispatching sprite
     * @param endedImage    the image the call ended and recycled
     * @param animationType the requested animation
     * @param force         nonzero to run the animation also while the sprite's nonbreaking flag ({@link Sprite#FLAG_ISCRIPT_NO_BREAK}, {@code 0x80}) is set
     * @throws IllegalStateException         if the image pool is missing or the ended image is still allocated
     * @throws UnsupportedOperationException if the request would run on a freed image
     */
    private void passFreedImages(@NotNull Sprite sprite, @NotNull SpriteImage endedImage, int animationType, int force) {
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (pool == null || pool.isAllocated(endedImage.getIndex())) {
            throw Exceptions.newIllegalStateException("An image detached by animation dispatch must be free in the native image pool.");
        }

        int poolIndex = pool.getNextFreeIndex(endedImage.getIndex());

        while (poolIndex >= 0) {
            SpriteImage freeImage = pool.getImage(poolIndex);

            if ((force != 0 || (byte) sprite.getFlags() >= 0) && !isAnimationRequestIgnored(freeImage, animationType & BYTE_MASK)) {
                throw Exceptions.newUnsupportedOperationException("Animation dispatch runs animation {0} on the freed image {1} " +
                                                                  "through its stale sprite pointer.",
                                                                  animationType & BYTE_MASK,
                                                                  poolIndex);
            }

            poolIndex = pool.getNextFreeIndex(poolIndex);
        }
    }

    /**
     * Clears the nonbreaking script state and runs the matching attack or spell idle transition.
     *
     * @param unit             the unit leaving its current script action
     * @param instructionLimit the cleanup animation instruction budget
     * @throws IllegalStateException if the unit lacks its sprite or main image
     */
    public void runToIdle(@NotNull Unit unit, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Script cleanup requires the retained main image.");
        }

        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.ISCRIPT_NO_BREAK);
        sprite.setFlags(sprite.getFlags() & ~Sprite.FLAG_ISCRIPT_NO_BREAK);

        int replacement = switch (sprite.getMainImage().getAnimationType()) {
            case ANIMATION_GROUND_ATTACK_INIT, ANIMATION_GROUND_ATTACK_REPEAT -> ANIMATION_GROUND_ATTACK_TO_IDLE;
            case ANIMATION_AIR_ATTACK_INIT, ANIMATION_AIR_ATTACK_REPEAT -> ANIMATION_AIR_ATTACK_TO_IDLE;
            case ANIMATION_CAST_SPELL -> ANIMATION_WALKING_TO_IDLE;
            case ANIMATION_SPECIAL_STATE_1 -> sprite.getSpriteTypeId() == SpriteIds.MEDIC ? ANIMATION_WALKING_TO_IDLE : -1;
            case ANIMATION_ALMOST_BUILT -> sprite.getSpriteTypeId() == SpriteIds.SCV || sprite.getSpriteTypeId() == SpriteIds.DRONE ||
                                           sprite.getSpriteTypeId() == SpriteIds.PROBE
                                           ? ANIMATION_GROUND_ATTACK_TO_IDLE
                                           : -1;
            default -> -1;
        };

        if (replacement >= 0) {
            this.runSpriteAnimation(sprite, unit, replacement, NOT_FORCED, instructionLimit);
        }

        unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.STARTING_ATTACK);
    }

    /**
     * Runs the primary active sprite effect loop.
     * <p>
     * The next sprite effect and image are taken before each invocation. The caller supplies the scheduling boundary. The later timestamp and
     * execution-scope paths and the secondary pool are excluded.
     *
     * @param instructionLimit the shared instruction bound for each sprite invocation
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the sprite effect pool is missing
     */
    public void updatePrimarySpriteEffects(int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        SpriteEffectPool pool = this.game.getSpriteSystem().getSpriteEffectPool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The native thingy pool is created with the game, before any frame runs.");
        }

        int poolIndex = pool.getFirstActiveIndex();

        while (poolIndex >= 0) {
            SpriteEffect spriteEffect = pool.getSpriteEffect(poolIndex);
            int followingIndex = pool.getNextIndex(poolIndex);
            Sprite sprite = spriteEffect.getSprite();

            if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
                LocalVision vision = this.game.getLocalVision();
                int spriteType = sprite.getSpriteTypeId();
                GameMap map = this.game.getMap();

                if (((spriteType - PLACEMENT_BOX_FIRST_SPRITE) & WORD_MASK) > PLACEMENT_BOX_LAST_SPRITE_OFFSET &&
                    spriteType < EXPLORED_SPRITE_LIMIT) {
                    // A replay that reveals the entire map shows these sprites to every player.
                    boolean revealed = this.game.isReplayPlayback() && vision.isRevealed();

                    SpriteVisibility.updateExplored(sprite, map, vision.getComparisonMask(), revealed);
                } else {
                    SpriteVisibility.updateSpritePlacementBox(sprite, sprite.getWidth(), sprite.getHeight(), map, vision.getComparisonMask());
                }
            }

            this.executeSprite(sprite, null, instructionLimit);

            if (sprite.getImages().isEmpty()) {
                pool.recycle(spriteEffect);
            }

            poolIndex = followingIndex;
        }
    }

    /**
     * Runs the primary sprite effect loop, then updates the flash timers of the stationary fog sprites of the secondary list and retires those that
     * became visible.
     *
     * @param secondaryPool    the separate pool of stationary fog sprites
     * @param instructionLimit the shared per-sprite instruction limit
     * @throws UnsupportedOperationException if the execution scope is missing or inactive, or a retired fog sprite references an unrepresented row
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if a fog sprite that became visible cannot be retired from established image and sprite pools
     */
    public void updateSpriteEffects(@NotNull SpriteEffectPool secondaryPool, int instructionLimit) {
        this.updatePrimarySpriteEffects(instructionLimit);

        GameExecutionScope executionScope = this.game.getExecutionScope();

        if (executionScope == null) {
            throw Exceptions.newUnsupportedOperationException("Native thingy scheduling requires its producer-backed executionScope context.");
        }

        executionScope.requireActiveScope();

        int poolIndex = secondaryPool.getFirstActiveIndex();

        while (poolIndex >= 0) {
            SpriteEffect spriteEffect = secondaryPool.getSpriteEffect(poolIndex);
            int followingIndex = secondaryPool.getNextIndex(poolIndex);
            Sprite sprite = spriteEffect.getSprite();

            this.game.getSelectionImages().updateFlash(sprite, sprite.getOwner());

            int unitType = spriteEffect.getLife() & WORD_MASK;
            int boxWidth = (short) this.game.requireGameData().getUnitTypes().getPlacementWidth(unitType);
            int boxHeight = (short) this.game.requireGameData().getUnitTypes().getPlacementHeight(unitType);
            int firstColumn = (short) (((short) sprite.getX() - boxWidth / PLACEMENT_BOX_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE);
            int firstRow = (short) (((short) sprite.getY() - boxHeight / PLACEMENT_BOX_CENTER_DIVISOR) / TerrainTileset.TILE_SIZE);
            int columns = (short) ((boxWidth + (TerrainTileset.TILE_SIZE - 1)) / TerrainTileset.TILE_SIZE);
            int rows = (short) ((boxHeight + (TerrainTileset.TILE_SIZE - 1)) / TerrainTileset.TILE_SIZE);
            GameMap map = this.game.getMap();
            boolean hidden = SpriteVisibility.isFogRectangleHidden(map, firstColumn, firstRow, columns, rows, this.game.getLocalVision());

            if (!hidden) {
                SpritePool spritePool = this.game.getSpriteSystem().getSpritePool();
                SpriteImagePool imagePool = this.game.getSpriteSystem().getImagePool();

                if (spritePool == null || imagePool == null) {
                    throw Exceptions.newIllegalStateException("Native fog sprite retirement requires established image and sprite pools.");
                }

                spritePool.removeFogMetadata(sprite);
                this.game.getSelectionImages().hideSelectionCircle(sprite);

                for (SpriteImage image : sprite.getImages()) {
                    imagePool.recycle(image);
                }

                sprite.clearImages();

                if (!spritePool.recycleEmptySprite(sprite, map.getHeight())) {
                    throw Exceptions.newUnsupportedOperationException("Native fog sprite retirement references an unrepresented row.");
                }

                secondaryPool.recycle(spriteEffect);
            }

            poolIndex = followingIndex;
        }
    }

    /**
     * Allocates a standalone sprite effect with its sprite, preserving the failure-list insertion.
     *
     * @param spriteType       the {@code sprites.dat} index
     * @param positionX        the requested X coordinate, truncated by the sprite constructor
     * @param positionY        the requested Y coordinate, truncated by the sprite constructor
     * @param owner            the owner byte
     * @param instructionLimit the limit for the actual sprite Init invocation
     * @return the initialized sprite effect, or {@code null} on allocation or bounds failure
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the sprite effect pool is missing
     */
    public @Nullable SpriteEffect createSpriteEffect(int spriteType, int positionX, int positionY, int owner, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        SpriteEffectPool pool = this.game.getSpriteSystem().getSpriteEffectPool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The native thingy pool is created with the game, before any thingy.");
        }

        SpriteEffect spriteEffect = pool.allocate();

        if (spriteEffect == null) {
            return null;
        }

        Sprite sprite = this.createSprite(spriteType, positionX, positionY, owner & BYTE_MASK, null, instructionLimit);

        pool.finishInitialization(spriteEffect, sprite);

        return sprite == null ? null : spriteEffect;
    }

    /**
     * Creates the hidden startup sprite effect ({@link SpriteIds#CURSOR_MARKER}) after pool linking.
     * <p>
     * The caller must establish fresh pools first. Failed startup allocation stops before publication instead of being treated as optional.
     *
     * @param instructionLimit the limit for the actual sprite Init invocation
     * @throws IllegalStateException if the storage is not fresh or the allocation fails
     */
    public void initializeSpriteEffects(int instructionLimit) {
        SpriteEffectPool pool = this.game.getSpriteSystem().getSpriteEffectPool();

        if (pool == null || pool.getAllocatedCount() != 0 || this.game.getSpriteSystem().getStartupSpriteEffect() != null) {
            throw Exceptions.newIllegalStateException("Native thingy startup requires fresh established storage.");
        }

        SpriteEffect spriteEffect = this.createSpriteEffect(SpriteIds.CURSOR_MARKER, 0, 0, STARTUP_SPRITE_EFFECT_OWNER, instructionLimit);

        if (spriteEffect == null) {
            throw Exceptions.newIllegalStateException("Native thingy startup dereferenced a failed sprite318 allocation.");
        }

        this.game.getSpriteSystem().setStartupSpriteEffect(spriteEffect);

        Sprite sprite = spriteEffect.getSprite();

        sprite.setFlags(sprite.getFlags() | Sprite.FLAG_HIDDEN);
        sprite.setVisibilityFlags(0);
    }

    /**
     * Allocates and initializes a sprite.
     * <p>
     * Coordinates are stored decoded. Registration retains the storage key and DWORD value. Image Init executes before dimensions, registration and
     * row insertion; unsupported Init effects remain explicit.
     *
     * @param spriteType       the {@code sprites.dat} index
     * @param positionX        the unsigned WORD horizontal coordinate
     * @param positionY        the unsigned WORD vertical coordinate
     * @param owner            the owner byte
     * @param unit             the active unit context, or {@code null}
     * @param instructionLimit the shared limit for image Init
     * @return the initialized sprite, or {@code null} on allocation or bounds failure
     * @throws IllegalArgumentException if the limit is not positive
     * @throws NullPointerException     if the world was built without game data
     * @throws IllegalStateException    if the sprite pool is missing, sprite initialization leaves no graphics or the sprite row lies outside the row lists
     */
    public @Nullable Sprite createSprite(int spriteType, int positionX, int positionY, int owner, @Nullable Unit unit, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        SpritePool pool = this.game.getSpriteSystem().getSpritePool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The native sprite pool is created with the game, before any sprite.");
        }

        Sprite sprite = pool.allocate();

        if (sprite == null) {
            return null;
        }

        positionX &= WORD_MASK;
        positionY &= WORD_MASK;

        if (positionX >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
            positionY >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE) {
            pool.recycleFailedInitialization(sprite);

            return null;
        }

        sprite.initializeNativeStorage(spriteType,
                                       positionX, positionY,
                                       owner,
                                       this.game.requireGameData().getSpriteTypes().getVisible(spriteType) != 0);

        int imageType = this.game.requireGameData().getSpriteTypes().getImage(spriteType);
        SpriteImage image = this.createImage(sprite, sprite.getMainImage(), imageType, 0, 0, 0, unit, instructionLimit);

        if (image == null) {
            pool.recycleFailedInitialization(sprite);

            return null;
        }

        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage == null || !mainImage.hasGraphics()) {
            throw Exceptions.newIllegalStateException("Native sprite initialization dereferenced graphics cleared by image Init.");
        }

        GrpFile graphics = mainImage.getGraphics();

        sprite.setNativeDimensions(graphics.getWidth(), graphics.getHeight());
        pool.register(sprite, 0);

        int rowIndex = SpritePool.decodeRow(positionY, this.game.getMap().getHeight());

        if (rowIndex >= GameMap.MAXIMUM_DIMENSION) {
            throw Exceptions.newIllegalStateException("The sprite row {0} is outside the native row lists.", rowIndex);
        }

        pool.attachToRow(sprite, rowIndex);

        return sprite;
    }

    /**
     * Copies a sprite for fog registration: a new sprite of the same type with copies of the source images except the health bar and selection circle
     * images.
     *
     * @param source           the original sprite whose image list is copied in reverse order
     * @param instructionLimit the instruction budget for the initial sprite construction
     * @return the copied sprite, or {@code null} on allocation failure
     * @throws IllegalStateException if the image or sprite pool is missing, or a copy whose image allocation failed cannot leave its row
     */
    public @Nullable Sprite copyFogSprite(@NotNull Sprite source, int instructionLimit) {
        Sprite destination = this.createSprite(source.getSpriteTypeId(), source.getX(), source.getY(), source.getOwner(), null, instructionLimit);

        if (destination == null) {
            return null;
        }

        SpriteImagePool imagePool = this.game.getSpriteSystem().getImagePool();
        SpritePool spritePool = this.game.getSpriteSystem().getSpritePool();

        if (imagePool == null || spritePool == null) {
            throw Exceptions.newIllegalStateException("Fog sprite copying requires initialized native image and sprite pools.");
        }

        for (SpriteImage image : destination.getImages()) {
            imagePool.recycle(image);
        }

        destination.clearImages();
        destination.setMainImage(null);

        for (int imageIndex = source.getImages().size() - 1; imageIndex >= 0; imageIndex--) {
            SpriteImage original = source.getImages().get(imageIndex);

            if (SelectionImages.isSelectionImage(original)) {
                continue;
            }

            SpriteImage copy = imagePool.allocate();

            if (copy == null) {
                for (SpriteImage image : destination.getImages()) {
                    imagePool.recycle(image);
                }

                destination.clearImages();

                if (!spritePool.recycleEmptySprite(destination, this.game.getMap().getHeight())) {
                    throw Exceptions.newIllegalStateException("Fog sprite copying could not unlink its sprite row.");
                }

                return null;
            }

            if (destination.getImages().isEmpty()) {
                destination.setMainImage(copy);
            }

            destination.insertHeadImage(copy);

            this.initializeImage(copy, destination, original.getImageTypeId(), original.getOffsetX(), original.getOffsetY());

            if ((copy.getFlags() & SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES) != 0) {
                int direction = original.getFrameIndexOffset();

                copy.setFrameIndexOffset(direction > LAST_UNFLIPPED_FRAME_DIRECTION ? FRAME_DIRECTION_COUNT - direction : direction);
            }

            copy.setFrameIndex(original.getFrameIndex());
            copy.setFlags((copy.getFlags() & ~SpriteImage.FLAG_HORIZONTALLY_FLIPPED) | (original.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED));

            if (original == source.getMainImage()) {
                destination.setMainImage(copy);
            }
        }

        return destination;
    }

    /**
     * Recycles the previous image list and initializes its replacement in the same sprite around the selection images, then applies the direction to
     * the sprite's images.
     *
     * @param unit             the sprite owner
     * @param imageType        the replacement {@code images.dat} index
     * @param direction        the replacement image direction
     * @param instructionLimit the initialization instruction budget
     * @throws IllegalStateException if the unit has no sprite
     */
    public void replaceSpriteImages(@NotNull Unit unit, int imageType, int direction, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Sprite image replacement requires its sprite.");
        }

        this.replacePrimaryImage(sprite, imageType, direction, unit, instructionLimit);
        applySpriteDirection(this.game, sprite, direction);
    }

    /**
     * Recycles the images of a sprite and creates its new primary image, with the selection images unlinked before and linked again after
     * ({@link SelectionImages#replaceImages}).
     *
     * @param sprite           the sprite
     * @param imageType        the new primary {@code images.dat} index
     * @param direction        the new primary image direction
     * @param unit             the sprite owner
     * @param instructionLimit the initialization instruction budget
     * @throws IllegalStateException if the image pool is missing
     */
    private void replacePrimaryImage(@NotNull Sprite sprite, int imageType, int direction, @NotNull Unit unit, int instructionLimit) {
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The native image pool is created with the game, before any image.");
        }

        this.game.getSelectionImages().replaceImages(sprite, () -> {
            for (SpriteImage image : sprite.getImages()) {
                pool.recycle(image);
            }

            sprite.clearImages();
            this.createImage(sprite, null, imageType, 0, 0, direction, unit, instructionLimit);
        });
    }

    /**
     * Runs the Init animation of the building warp texture script on the unit's main image, then restores the image's own script identifier, sets the
     * warp texture draw function and runs the image's script once.
     *
     * @param unit             the warping building
     * @param instructionLimit the shared script instruction budget
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the unit has no main image
     * @throws NullPointerException     if the world was built without game data
     */
    public void initializeWarpTexture(@NotNull Unit unit, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Warp texture initialization requires its main image.");
        }

        SpriteImage image = sprite.getMainImage();
        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            image.setScriptIdentifier(WARP_TEXTURE_SCRIPT);
            this.runAnimation(preview, image, sprite, unit, WARP_TEXTURE_SCRIPT, ANIMATION_INIT);
            image.setScriptIdentifier(this.game.requireGameData().getImageTypes().getIscript(image.getImageTypeId()));
            image.setModifier(SpriteImage.MODIFIER_WARP_TEXTURE);
            image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            this.execute(preview, image, sprite, unit);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Replaces the initial building images with the construction image (or the sprite's image) around the selection images, then restores the
     * direction, the cloak modifier and the status effects.
     *
     * @param unit             the unit whose sprite is replaced
     * @param constructionMode nonzero to prefer the unit type's construction image when it is nonzero
     * @param instructionLimit the shared instruction limit for replacement Init and child images
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the unit lacks its sprite or a required main image, or the damage overlay cannot be initialized
     */
    public void initializeConstructionImage(@NotNull Unit unit, int constructionMode, int instructionLimit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Native construction image initialization dereferences an absent sprite.");
        }

        boolean concealed = (unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) != 0;
        int retainedCloakData = 0;

        if (concealed) {
            SpriteImage previousMainImage = sprite.getMainImage();

            if (previousMainImage == null) {
                throw Exceptions.newIllegalStateException("Native construction image concealment dereferences an absent main image.");
            }

            retainedCloakData = previousMainImage.getModifierData1();
        }

        int imageType = constructionMode != 0 ? this.game.requireGameData().getUnitTypes().getConstructionImage(unit.getUnitId()) : 0;

        if (imageType == 0) {
            imageType = this.game.requireGameData().getSpriteTypes().getImage(sprite.getSpriteTypeId());
        }

        this.replacePrimaryImage(sprite, imageType, 0, unit, instructionLimit);
        applySpriteDirection(this.game, sprite, unit.getHeading());

        // Restore the cloak modifier with the retained data before applying unit effects.
        if (concealed) {
            this.game.getUnitCloaking().setSpriteCloakModifier(sprite,
                                                               (unit.getStatus() & UnitStatusFlags.REQUIRES_DETECTOR) != 0,
                                                               (unit.getStatus() & UnitStatusFlags.CLOAKED) != 0,
                                                               (unit.getStatus() & UnitStatusFlags.BURROWED) != 0,
                                                               retainedCloakData);
        }

        this.game.getUnitStatusEffects().apply(unit, instructionLimit);

        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage == null) {
            throw Exceptions.newIllegalStateException("Native construction image initialization dereferences an absent main image.");
        }

        try {
            unit.setDamageOverlayCount(this.game.requireGameData().getImageDamageOverlayCount(mainImage.getImageTypeId()));
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Native construction damage overlay initialization failed.");
        }
    }

    /**
     * Creates image storage, links it before Init, and invokes the checked instruction subset.
     * <p>
     * The supplied sprite and unit context must already be established. Sprite allocation, viewport geometry caches and complete constructor success
     * are not established by this bounded operation. An image ended by Init is returned as recycled storage with its retained object identity.
     *
     * @param sprite           the owning sprite
     * @param referenceImage   the insertion reference, required when the sprite list is not empty
     * @param imageType        the {@code images.dat} index
     * @param offsetX          the initial horizontal position byte
     * @param offsetY          the initial vertical position byte
     * @param direction        the post-Init direction byte
     * @param unit             the unit context, or {@code null}
     * @param instructionLimit the shared diagnostic instruction limit for Init and child images
     * @return the allocated image storage, including if Init recycled it, or {@code null} on exhaustion
     * @throws IllegalArgumentException if the instruction limit is not positive
     * @throws IllegalStateException    if the image pool is missing or a nonempty sprite lacks an attached reference image
     */
    public @Nullable SpriteImage createImage(@NotNull Sprite sprite,
                                             @Nullable SpriteImage referenceImage,
                                             int imageType,
                                             int offsetX, int offsetY,
                                             int direction,
                                             @Nullable Unit unit,
                                             int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The native image pool is created with the game, before any image.");
        }

        if (pool.getFirstFreeIndex() < 0) {
            return null;
        }

        int referenceIndex = sprite.getImages().indexOf(referenceImage);

        if (!sprite.getImages().isEmpty() && referenceIndex < 0) {
            throw Exceptions.newIllegalStateException("Native image insertion needs an attached reference.");
        }

        SpriteImage image = pool.allocate();

        if (image == null) {
            return null;
        }

        if (referenceImage == null || sprite.getImages().isEmpty()) {
            sprite.setMainImage(image);
            sprite.insertHeadImage(image);
        } else {
            sprite.insertImageAbove(referenceImage, image);
        }

        this.initializeImage(image, sprite, imageType, offsetX, offsetY);
        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            this.runAnimation(preview, image, sprite, unit, image.getScriptIdentifier(), ANIMATION_INIT);
        } finally {
            this.releaseExecution();
        }

        if ((image.getFlags() & SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES) != 0) {
            direction &= BYTE_MASK;

            boolean flipped = direction > LAST_UNFLIPPED_FRAME_DIRECTION;
            int frameOffset = flipped ? (FRAME_DIRECTION_COUNT - direction) & BYTE_MASK : direction;

            if (image.getFrameIndexOffset() != frameOffset || ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) != flipped) {
                image.setFrameIndexOffset(frameOffset);
                image.setFlags((image.getFlags() & ~SpriteImage.FLAG_HORIZONTALLY_FLIPPED) |
                               (flipped ? SpriteImage.FLAG_HORIZONTALLY_FLIPPED : 0) | SpriteImage.FLAG_REDRAW);
            }
        }

        return image;
    }

    /**
     * Applies the animation request gate and invokes the bounded image preview subset.
     *
     * @param image            the inspected image
     * @param sprite           the image's sprite
     * @param scriptIdentifier the iscript identifier
     * @param animationType    the requested animation, truncated to an unsigned byte
     * @param instructionLimit the maximum instructions in this invocation
     * @return {@code true} if ignored or still alive, {@code false} otherwise
     * @throws IllegalArgumentException      if the limit is not positive
     * @throws IllegalStateException         if lookup or decoding fails or the instruction limit runs out
     * @throws UnsupportedOperationException if execution meets an operation outside the checked subset
     */
    public boolean runImagePreviewAnimation(@NotNull SpriteImage image,
                                            @NotNull Sprite sprite,
                                            int scriptIdentifier,
                                            int animationType,
                                            int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            return this.runAnimation(preview, image, sprite, null, scriptIdentifier, animationType);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Switches the image to the given animation and immediately invokes its interpreter.
     * <p>
     * The animation argument is truncated to an unsigned byte. Repeated Death, Walking and IsWorking requests are ignored; requests above Death
     * require the full-iscript image flag. Attack-repeat requests select their initial animation when the current category differs.
     *
     * @param image            the image
     * @param sprite           the sprite the image belongs to
     * @param unit             the unit context, or {@code null} for unit-free animations
     * @param scriptIdentifier the iscript identifier
     * @param animationType    the animation type
     * @return {@code true} if ignored or still alive, {@code false} otherwise
     */
    public boolean runAnimation(@NotNull SpriteImage image, @NotNull Sprite sprite, @Nullable Unit unit, int scriptIdentifier, int animationType) {
        return this.runAnimation(null, image, sprite, unit, scriptIdentifier, animationType);
    }

    /**
     * Switches the image to the given animation and invokes its interpreter within an optional bounded invocation.
     *
     * @param preview          the bounded invocation, or {@code null} for an unbounded frame invocation
     * @param image            the image
     * @param sprite           the sprite the image belongs to
     * @param unit             the unit context, or {@code null}
     * @param scriptIdentifier the iscript identifier
     * @param animationType    the animation type
     * @return {@code true} if ignored or still alive, {@code false} otherwise
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the iscript offset of a request that is not ignored cannot be read
     */
    private boolean runAnimation(@Nullable IscriptExecution preview,
                                 @NotNull SpriteImage image,
                                 @NotNull Sprite sprite,
                                 @Nullable Unit unit,
                                 int scriptIdentifier, int animationType) {
        animationType &= BYTE_MASK;

        int currentAnimation = image.getAnimationType();

        if (isAnimationRequestIgnored(image, animationType)) {
            return true;
        }

        if (animationType == ANIMATION_GROUND_ATTACK_REPEAT && currentAnimation != ANIMATION_GROUND_ATTACK_REPEAT &&
            currentAnimation != ANIMATION_GROUND_ATTACK_INIT) {
            animationType = ANIMATION_GROUND_ATTACK_INIT;
        } else if (animationType == ANIMATION_AIR_ATTACK_REPEAT && currentAnimation != ANIMATION_AIR_ATTACK_REPEAT &&
                   currentAnimation != ANIMATION_AIR_ATTACK_INIT) {
            animationType = ANIMATION_AIR_ATTACK_INIT;
        }

        GameData gameData = this.game.requireGameData();
        int animationOffset;

        try {
            animationOffset = gameData.getIscript().getAnimationOffset(scriptIdentifier, animationType);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Failed to resolve iscript {0} animation {1}", scriptIdentifier, animationType);
        }

        // Start the animation of the requested script at its offset with a cleared return address and wait.
        SpriteImagePool pool = image.getPool();

        pool.bindIscript(gameData.getIscriptProgram(), gameData.getIscriptOffsets());
        pool.getAnimationState().start(image.getIndex(),
                                       gameData.getIscriptLibrary().getSet(scriptIdentifier),
                                       animationType,
                                       gameData.getIscriptProgram(),
                                       gameData.getIscriptOffsets().getPosition(animationOffset));
        image.setReturnAddress(0);

        return this.execute(preview, image, sprite, unit);
    }

    /**
     * Creates an image next to a reference image of the same sprite and runs its Init script.
     * <p>
     * The cloak and hallucination state of the unit does not change the new image; only the iscript image opcodes apply it
     * ({@link #createScriptImage}).
     *
     * @param preview          the bounded invocation; {@code null} fails unless image storage is exhausted
     * @param referenceImage   the creating image
     * @param sprite           the sprite of both images
     * @param imageType        the {@code images.dat} index
     * @param offsetX          the initial horizontal offset
     * @param offsetY          the initial vertical offset
     * @param order            {@link #ORDER_ABOVE} or {@link #ORDER_BELOW}
     * @param unit             the unit context, or {@code null}
     * @param inheritDirection whether the image takes the reference image's direction
     * @return the created image, or {@code null} when image storage is exhausted
     * @throws IllegalStateException if the creation exceeds the nesting depth limit or the reference image is not attached to the sprite and its pool
     * @throws NullPointerException  if {@code preview} is {@code null} and image storage is not exhausted
     */
    public @Nullable SpriteImage createImagePreview(@Nullable IscriptExecution preview,
                                                    @NotNull SpriteImage referenceImage,
                                                    @NotNull Sprite sprite,
                                                    int imageType,
                                                    int offsetX, int offsetY,
                                                    int order,
                                                    @Nullable Unit unit,
                                                    boolean inheritDirection) {
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (pool != null && pool.getFirstFreeIndex() < 0) {
            return null;
        }

        if (preview == null) {
            throw Exceptions.newNullPointerException("Image creation requires bounded pooled execution.");
        }

        if (preview.getCreationDepth() >= MAXIMUM_CREATION_DEPTH) {
            throw Exceptions.newIllegalStateException("Image preview creation depth limit reached at image {0} from offset {1}",
                                                      imageType,
                                                      referenceImage.getProgramCounter());
        }

        int referenceIndex = sprite.getImages().indexOf(referenceImage);

        if (referenceIndex < 0) {
            throw Exceptions.newIllegalStateException("The creating image is not attached to the inspected sprite.");
        }

        if (pool == null || pool.getImage(referenceImage.getIndex()) != referenceImage || !pool.isAllocated(referenceImage.getIndex())) {
            throw Exceptions.newIllegalStateException("Image creation requires the reference image's owning pool.");
        }

        SpriteImage image = pool.allocate();

        if (image == null) {
            return null;
        }

        this.initializeImage(image, sprite, imageType, offsetX, offsetY);

        if (order == ORDER_ABOVE) {
            sprite.insertImageAbove(referenceImage, image);
        } else {
            sprite.insertImageBelow(referenceImage, image);
        }

        preview.enterCreation();

        try {
            this.runAnimation(preview, image, sprite, unit, image.getScriptIdentifier(), ANIMATION_INIT);
        } finally {
            preview.leaveCreation();
        }

        if (inheritDirection && (image.getFlags() & SpriteImage.FLAG_HAS_DIRECTIONAL_FRAMES) != 0) {
            int direction = (referenceImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0
                            ? (FRAME_DIRECTION_COUNT - referenceImage.getFrameIndexOffset()) & BYTE_MASK
                            : referenceImage.getFrameIndexOffset();
            boolean flipped = direction > LAST_UNFLIPPED_FRAME_DIRECTION;
            int frameOffset = flipped ? (FRAME_DIRECTION_COUNT - direction) & BYTE_MASK : direction;

            if (image.getFrameIndexOffset() != frameOffset || ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) != flipped) {
                image.setFrameIndexOffset(frameOffset);
                image.setFlags((image.getFlags() & ~SpriteImage.FLAG_HORIZONTALLY_FLIPPED) |
                               (flipped ? SpriteImage.FLAG_HORIZONTALLY_FLIPPED : 0) | SpriteImage.FLAG_REDRAW);

                if ((image.getFlags() & SpriteImage.FLAG_USES_SPECIAL_OFFSET) != 0) {
                    this.updateSpecialOffset(image);
                }
            }
        }

        return image;
    }

    /**
     * Creates the image of an iscript image opcode next to the creating image and runs its Init script, then applies the state of the script's unit
     * to it.
     * <p>
     * A new image with draw function {@code 0} of a hallucination takes the hallucination draw function when the view shows hallucinations as such
     * ({@code isHallucinationShown}). Then, while the unit is cloaked or requires a detector, a new image that {@code images.dat} does not draw
     * while cloaked is hidden, and a new image with draw function {@code 0} that is drawn while cloaked takes the cloak draw function and the fade
     * data of the sprite's primary image when that image uses a cloak draw function.
     *
     * @param preview        the bounded invocation; {@code null} fails unless image storage is exhausted
     * @param referenceImage the creating image
     * @param sprite         the sprite of both images
     * @param imageType      the {@code images.dat} index
     * @param offsetX        the initial horizontal offset
     * @param offsetY        the initial vertical offset
     * @param order          {@link #ORDER_ABOVE} or {@link #ORDER_BELOW}
     * @param unit           the unit context, or {@code null}
     * @return the created image, or {@code null} when image storage is exhausted
     * @throws IllegalStateException if the sprite of a cloaked unit has no primary image
     * @throws NullPointerException  if the world was built without game data, or {@code preview} is {@code null} and image storage is not exhausted
     */
    public @Nullable SpriteImage createScriptImage(@Nullable IscriptExecution preview,
                                                   @NotNull SpriteImage referenceImage,
                                                   @NotNull Sprite sprite,
                                                   int imageType,
                                                   int offsetX, int offsetY,
                                                   int order,
                                                   @Nullable Unit unit) {
        SpriteImage image = this.createImagePreview(preview, referenceImage, sprite, imageType, offsetX, offsetY, order, unit, true);

        if (image == null || unit == null) {
            return image;
        }

        if (image.getModifier() == ORDINARY_IMAGE_MODIFIER && (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 &&
            this.isHallucinationShown(unit)) {
            image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            image.setModifier(SpriteImage.MODIFIER_HALLUCINATION);
            image.setModifierData1(0);
        }

        if ((unit.getStatus() & (UnitStatusFlags.REQUIRES_DETECTOR | UnitStatusFlags.CLOAKED)) == 0) {
            return image;
        }

        if (this.game.requireGameData().getImageTypes().getDrawIfCloaked(imageType) == 0) {
            image.setFlags(image.getFlags() | SpriteImage.FLAG_HIDDEN);

            return image;
        }

        if (image.getModifier() != ORDINARY_IMAGE_MODIFIER) {
            return image;
        }

        SpriteImage primaryImage = sprite.getMainImage();

        if (primaryImage == null) {
            throw Exceptions.newIllegalStateException("The overlay of cloaked unit type {0} needs the primary image of its sprite.",
                                                      unit.getUnitId());
        }

        int primaryModifier = primaryImage.getModifier();

        if (primaryModifier >= SpriteImage.MODIFIER_FIRST_CLOAK && primaryModifier <= SpriteImage.MODIFIER_LAST_CLOAK) {
            image.setModifier(primaryModifier);
            image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
            image.setModifierData1(image.getModifierData1() & HIGH_WORD_MASK | primaryImage.getModifierData1() & WORD_MASK);
        }

        return image;
    }

    /**
     * @param unit the hallucination
     * @return {@code true} if the view shows the hallucination as such: a replay or observer view, or a hallucination of the local player, {@code false} otherwise
     */
    private boolean isHallucinationShown(@NotNull Unit unit) {
        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();

        return this.game.getLocalVision().isReplayOrObserver() || (creation != null && unit.getOwnerIndex() == creation.getLocalPlayer());
    }

    /**
     * Initializes allocated image storage from {@code images.dat}: the flags, the draw function, the graphics and the script. Of the draw functions,
     * remapping takes its color shift as the color remapping index, player color the sprite owner and the warp-in flash its start data as modifier
     * data; every other one is only stored.
     *
     * @param image     the allocated image
     * @param sprite    the sprite that owns the image
     * @param imageType the {@code images.dat} index
     * @param offsetX   the initial horizontal offset
     * @param offsetY   the initial vertical offset
     * @throws NullPointerException if the world was built without game data
     */
    private void initializeImage(@NotNull SpriteImage image, @NotNull Sprite sprite, int imageType, int offsetX, int offsetY) {
        ScrImageTypeView imageTypes = this.game.requireGameData().getImageTypes();
        int modifier = imageTypes.getDrawFunction(imageType);
        int flags = SpriteImage.FLAG_REDRAW |
                    (imageTypes.getGraphicTurns(imageType) & FLAG_BIT_MASK) << DIRECTIONAL_FRAMES_FLAG_SHIFT |
                    (imageTypes.getFullIscript(imageType) & FLAG_BIT_MASK) << FULL_ISCRIPT_FLAG_SHIFT |
                    (imageTypes.getClickable(imageType) & FLAG_BIT_MASK) << CLICKABLE_FLAG_SHIFT;

        image.initializeNativeStorage(imageType, offsetX, offsetY, flags, modifier);

        if (modifier == SpriteImage.MODIFIER_REMAPPING) {
            image.setColorRemappingIndex(imageTypes.getColorShift(imageType));
        } else if (modifier == SpriteImage.MODIFIER_PLAYER_COLOR) {
            image.setModifierData1(sprite.getOwner());
        } else if (modifier == SpriteImage.MODIFIER_WARP_FLASH) {
            image.setModifierData1(SpriteImage.WARP_FLASH_START_DATA);
        }

        image.setGraphics(this.getGrpFile(image));
        image.setScriptIdentifier(imageTypes.getIscript(imageType));
    }

    /**
     * Creates the actual mineral carry image and its Init overlays from the image pool.
     *
     * @param worker           the carrying worker
     * @param small            whether the final partial resource chunk uses {@link ImageIds#MINERAL_CHUNK_TYPE2}
     * @param instructionLimit the shared creation script budget
     * @throws IllegalArgumentException if the instruction limit is not positive
     * @throws IllegalStateException    if the worker has no main image
     */
    public void addMineralCarryImage(@NotNull Unit worker, boolean small, int instructionLimit) {
        this.addResourceCarryImage(worker, small ? ImageIds.MINERAL_CHUNK_TYPE2 : ImageIds.MINERAL_CHUNK_TYPE1, instructionLimit);
    }

    /**
     * Creates the refinery-specific gas carry image from the shared image pool.
     *
     * @param worker           the carrying worker
     * @param refineryType     the refinery unit type
     * @param small            whether to use the depleted image, one above {@link ImageIds#TERRAN_GAS_TANK_TYPE1}, {@link ImageIds#ZERG_GAS_SAC_TYPE1} or {@link ImageIds#PROTOSS_GAS_ORB_TYPE1}
     * @param instructionLimit the shared creation script budget
     * @throws IllegalArgumentException if the unit type is not a refinery or the instruction limit is not positive
     * @throws IllegalStateException    if the worker has no main image
     */
    public void addGasCarryImage(@NotNull Unit worker, int refineryType, boolean small, int instructionLimit) {
        int imageType = switch (ScrUnitIds.orNone(refineryType)) {
            case ScrUnitIds.TERRAN_REFINERY -> ImageIds.TERRAN_GAS_TANK_TYPE1;
            case ScrUnitIds.ZERG_EXTRACTOR -> ImageIds.ZERG_GAS_SAC_TYPE1;
            case ScrUnitIds.PROTOSS_ASSIMILATOR -> ImageIds.PROTOSS_GAS_ORB_TYPE1;
            default -> throw Exceptions.newIllegalArgumentException("The resource is not a refinery: {0}", refineryType);
        };

        this.addResourceCarryImage(worker, imageType + (small ? 1 : 0), instructionLimit);
    }

    /**
     * Creates a carried resource image above the worker's main image at its special overlay offset.
     *
     * @param worker           the carrying worker
     * @param imageType        the carried image
     * @param instructionLimit the shared creation script budget
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the worker has no main image
     */
    private void addResourceCarryImage(@NotNull Unit worker, int imageType, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        Sprite sprite = worker.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Carried resources require the worker main image.");
        }

        SpriteImage referenceImage = sprite.getMainImage();

        // The carried image starts with direction 0; the worker's next turn updates it.
        IscriptExecution preview = this.acquireExecution(instructionLimit);
        SpriteImage image;

        try {
            image = this.createImagePreview(preview, referenceImage, sprite, imageType, 0, 0, ORDER_ABOVE, worker, false);
        } finally {
            this.releaseExecution();
        }

        if (image != null && (image.getFlags() & SpriteImage.FLAG_USES_SPECIAL_OFFSET) == 0) {
            image.setFlags(image.getFlags() | SpriteImage.FLAG_USES_SPECIAL_OFFSET);
            this.updateSpecialOffset(image);
        }
    }

    /**
     * Adds a carried powerup's image to a worker with direction {@code 0}, the powerup owner's color for the player color draw function and the
     * special overlay offset.
     *
     * @param worker           the carrying worker
     * @param imageType        the carried image
     * @param owner            the powerup's owner
     * @param instructionLimit the animation budget
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the worker has no main image
     */
    public void addPowerupCarryImage(@NotNull Unit worker, int imageType, int owner, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        Sprite sprite = worker.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("A carried powerup requires the worker main image.");
        }

        SpriteImage referenceImage = sprite.getMainImage();

        IscriptExecution preview = this.acquireExecution(instructionLimit);
        SpriteImage image;

        try {
            image = this.createImagePreview(preview, referenceImage, sprite, imageType, 0, 0, ORDER_ABOVE, worker, false);
        } finally {
            this.releaseExecution();
        }

        if (image == null) {
            return;
        }

        if (image.getModifier() == SpriteImage.MODIFIER_PLAYER_COLOR) {
            image.setModifierData1(owner & BYTE_MASK);
        }

        if ((image.getFlags() & SpriteImage.FLAG_USES_SPECIAL_OFFSET) == 0) {
            image.setFlags(image.getFlags() | SpriteImage.FLAG_USES_SPECIAL_OFFSET);
            this.updateSpecialOffset(image);
        }
    }

    /**
     * Recycles the carrying-image ranges in three-pass destruction order: the images after the carried powerup images, the carried powerup and
     * resource images, then the carried Uraj and Khalis crystals.
     *
     * @param worker the carrying worker
     * @throws IllegalStateException if the worker has no sprite or the image pool is missing
     */
    public void removeCarryImages(@NotNull Unit worker) {
        Sprite sprite = worker.getSpriteOrNull();
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (sprite == null || pool == null) {
            throw Exceptions.newIllegalStateException("Carrying images require retained sprite and image storage.");
        }

        for (int range = 0; range < CARRYING_RANGE_COUNT; range++) {
            int minimum = range == CARRIED_EXTRA_RANGE ? CARRIED_EXTRA_FIRST_IMAGE : range == CARRIED_ITEM_RANGE ? ImageIds.FLAG : ImageIds.URAJ;
            int maximum = range == CARRIED_EXTRA_RANGE ? CARRIED_EXTRA_LAST_IMAGE
                                                       : range == CARRIED_ITEM_RANGE ? ImageIds.TERRAN_GAS_TANK_TYPE2 : ImageIds.KHALIS;

            for (int position = 0; position < sprite.getImages().size(); ) {
                SpriteImage image = sprite.getImages().get(position);

                if (image.getImageTypeId() >= minimum && image.getImageTypeId() <= maximum) {
                    sprite.removeImage(image);
                    pool.recycle(image);
                } else {
                    position++;
                }
            }
        }
    }

    /**
     * Creates an image directly below the sprite's main image.
     *
     * @param unit             the unit whose sprite receives the image
     * @param imageType        the {@code images.dat} index
     * @param instructionLimit the shared creation script budget
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the unit has no main image
     */
    public void createImageBelowMain(@NotNull Unit unit, int imageType, int instructionLimit) {
        checkInstructionLimit(instructionLimit);

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Image creation below the main image requires the unit main image.");
        }

        SpriteImage referenceImage = sprite.getMainImage();

        IscriptExecution preview = this.acquireExecution(instructionLimit);

        try {
            this.createImagePreview(preview, referenceImage, sprite, imageType, 0, 0, ORDER_BELOW, unit, true);
        } finally {
            this.releaseExecution();
        }
    }

    /**
     * Detaches and recycles one image.
     *
     * @param image the attached image
     * @throws IllegalStateException if the image pool or the image's sprite is missing
     */
    public void destroyImage(@NotNull SpriteImage image) {
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();
        Sprite sprite = image.getSprite();

        if (pool == null || sprite == null) {
            throw Exceptions.newIllegalStateException("Image destruction requires retained image storage and its sprite.");
        }

        sprite.removeImage(image);
        pool.recycle(image);
    }

    /**
     * Recycles the first image of a type in list order.
     *
     * @param sprite    the sprite whose image list is searched
     * @param imageType the {@code images.dat} index to remove
     * @throws IllegalStateException if the image pool is missing
     */
    public void destroyFirstImage(@NotNull Sprite sprite, int imageType) {
        SpriteImagePool pool = this.game.getSpriteSystem().getImagePool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("Image destruction requires retained image storage.");
        }

        for (SpriteImage image : sprite.getImages()) {
            if (image.getImageTypeId() == imageType) {
                sprite.removeImage(image);
                pool.recycle(image);

                return;
            }
        }
    }

    /**
     * Moves an image to the special overlay offset of its sprite's main image frame, mirrored with a flipped main image.
     *
     * @param image the image using the special offset
     * @throws IllegalStateException if the sprite has no main image or the offsets cannot be read
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateSpecialOffset(@NotNull SpriteImage image) {
        SpriteImage mainImage = image.requireSprite().getMainImage();

        if (mainImage == null) {
            throw Exceptions.newIllegalStateException("Special offsets require the sprite main image.");
        }

        int offset;

        try {
            offset = this.game.requireGameData().getImageSpecialOverlayOffset(mainImage.getImageTypeId(), mainImage.getDrawnFrameIndex(), 0);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Cannot load special image offsets.");
        }

        int horizontal = (short) offset;
        int vertical = (short) (offset >>> Short.SIZE);

        if ((mainImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
            horizontal = -horizontal;
        }

        if (image.getOffsetX() != horizontal || image.getOffsetY() != vertical) {
            image.setOffsetX(horizontal);
            image.setOffsetY(vertical);
            image.setFlags(image.getFlags() | SpriteImage.FLAG_REDRAW);
        }
    }

    /**
     * Creates a sprite effect at an image's position plus an offset, with the given elevation, visibility and optionally the image's heading.
     *
     * @param image            the creating image
     * @param sprite           the creating image's sprite
     * @param spriteType       the {@code sprites.dat} index
     * @param offsetX          the horizontal offset from the image
     * @param offsetY          the vertical offset from the image
     * @param elevation        the elevation level of the created sprite
     * @param unit             the unit context, or {@code null}
     * @param inheritHeading   whether the created sprite takes the image's heading
     * @param instructionLimit the instruction limit of the sprite's Init
     * @throws UnsupportedOperationException if the sprite effect pool is missing
     */
    public void createSpriteEffectAtImage(@NotNull SpriteImage image,
                                          @NotNull Sprite sprite,
                                          int spriteType,
                                          int offsetX, int offsetY,
                                          int elevation,
                                          @Nullable Unit unit,
                                          boolean inheritHeading,
                                          int instructionLimit) {
        SpriteEffectPool pool = this.game.getSpriteSystem().getSpriteEffectPool();

        if (pool == null) {
            throw Exceptions.newUnsupportedOperationException("Script sprite effects require the initialized primary thingy pool.");
        }

        SpriteEffect effect = pool.allocate();

        if (effect == null) {
            return;
        }

        Sprite created = this.createSprite(spriteType,
                                           sprite.getX() + image.getOffsetX() + offsetX,
                                           sprite.getY() + image.getOffsetY() + offsetY,
                                           SCRIPT_SPRITE_EFFECT_OWNER,
                                           unit,
                                           instructionLimit);

        pool.finishInitialization(effect, created);

        if (created == null) {
            return;
        }

        created.setElevationLevel(elevation);

        SpriteVisibility.updatePositionTile(created, this.game.getMap(), this.game.getLocalVision().getComparisonMask());

        if (inheritHeading) {
            int frameDirection = image.getFrameIndexOffset();

            if ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
                frameDirection = FRAME_DIRECTION_COUNT - frameDirection;
            }

            applySpriteDirection(this.game, created, (frameDirection * FlingyMotionUtils.DIRECTION_THIRTY_SECOND_TURN) & BYTE_MASK);
        }
    }

    /**
     * Plays a sound at a map position ({@link UnitSounds#playAt}).
     *
     * @param soundId   the {@code sfxdata.dat} index
     * @param positionX the horizontal map position
     * @param positionY the vertical map position
     */
    public void playSound(int soundId, int positionX, int positionY) {
        this.game.getUnitSounds().playAt(soundId, positionX, positionY);
    }
}
