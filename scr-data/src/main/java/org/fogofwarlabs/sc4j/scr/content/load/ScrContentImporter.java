package org.fogofwarlabs.sc4j.scr.content.load;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.AnimationLibrary;
import org.fogofwarlabs.sc4j.engine.animation.AnimationSet;
import org.fogofwarlabs.sc4j.engine.content.definition.*;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceCost;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.faction.FactionDefinition;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentRegistry;
import org.fogofwarlabs.sc4j.engine.content.type.*;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDerivedValueUtils;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Registers the SC:R content from the installed DAT tables: the resource kinds, the races and every type of the nine
 * DAT tables with its engine definition, plus one extension column per DAT field that no engine column holds
 * ({@link ScrDatKind}) and the unit trait columns ({@link ScrUnitTraits}).
 * <p>
 * Every kind is registered in native order, so the index of each type is its DAT entry; the registration fails if an
 * identity would get another index. The engine columns start from the definitions and the extension columns from a copy
 * of the DAT values taken at registration, so every game of the content starts from the same values. A type reference
 * whose DAT value is the native "none" entry is registered as no type; any other value outside its table fails the
 * registration.
 * <p>
 * The added unit types of a mod ({@link ScrAddedUnitTypes}) are registered right after the native unit types, in their order, with the
 * definition, extension column and trait column values of their registration; their engine index is their SC:R id minus
 * {@link ScrAddedUnitTypes#CONTENT_INDEX_SHIFT}. The added images, sprites and flingy entries of a mod ({@link ScrAddedGraphics}) are registered
 * right after the native entries of their kind, with the definition and extension column values of their registration; their engine index is
 * their id. Without added types and entries the content is the native one.
 */
public final class ScrContentImporter {
    /**
     * The number of player slots of the per-player tables.
     */
    public static final int PLAYER_COUNT = 12;

    /**
     * The value of a reference kind without a native "none" entry.
     */
    private static final int NO_NATIVE_NONE = Integer.MIN_VALUE;

    private final @NotNull DatTable units;
    private final @NotNull DatTable weapons;
    private final @NotNull DatTable flingy;
    private final @NotNull DatTable sprites;
    private final @NotNull DatTable images;
    private final @NotNull DatTable orders;
    private final @NotNull DatTable upgrades;
    private final @NotNull DatTable techdata;
    private final @NotNull DatTable sfxdata;
    private final @NotNull AnimationLibrary animations;
    private final @NotNull ScrAddedUnitTypes addedUnits;
    private final @NotNull ScrAddedGraphics addedGraphics;

    private final @NotNull List<@NotNull ImageType> imageTypes;
    private final @NotNull List<@NotNull SpriteType> spriteTypes;
    private final @NotNull List<@NotNull MotionProfile> flingyTypes;
    private final @NotNull List<@NotNull WeaponType> weaponTypes;

    /**
     * Creates a {@link ScrContentImporter} instance over the installed DAT tables, which the registration reads and does
     * not change.
     *
     * @param units         the unit type table ({@code units.dat})
     * @param weapons       the weapon type table ({@code weapons.dat})
     * @param flingy        the flingy type table ({@code flingy.dat})
     * @param sprites       the sprite type table ({@code sprites.dat})
     * @param images        the image type table ({@code images.dat})
     * @param orders        the order type table ({@code orders.dat})
     * @param upgrades      the upgrade type table ({@code upgrades.dat})
     * @param techdata      the tech type table ({@code techdata.dat})
     * @param sfxdata       the sound type table ({@code sfxdata.dat})
     * @param animations    the animation library of the iscript with the added scripts, whose sets the image types name
     * @param addedUnits    the added unit types registered after the native ones, closed by the registration
     * @param addedGraphics the added images, sprites and flingy entries registered after the native ones, closed by the registration
     */
    public ScrContentImporter(@NotNull DatTable units,
                              @NotNull DatTable weapons,
                              @NotNull DatTable flingy,
                              @NotNull DatTable sprites,
                              @NotNull DatTable images,
                              @NotNull DatTable orders,
                              @NotNull DatTable upgrades,
                              @NotNull DatTable techdata,
                              @NotNull DatTable sfxdata,
                              @NotNull AnimationLibrary animations,
                              @NotNull ScrAddedUnitTypes addedUnits,
                              @NotNull ScrAddedGraphics addedGraphics) {
        this.units    = units;
        this.weapons  = weapons;
        this.flingy   = flingy;
        this.sprites  = sprites;
        this.images   = images;
        this.orders   = orders;
        this.upgrades = upgrades;
        this.techdata = techdata;
        this.sfxdata  = sfxdata;

        this.animations    = animations;
        this.addedUnits    = addedUnits;
        this.addedGraphics = addedGraphics;

        this.imageTypes  = new ArrayList<>(ScrImages.getTypes());
        this.spriteTypes = new ArrayList<>(ScrSprites.getTypes());
        this.flingyTypes = new ArrayList<>(ScrFlingies.getTypes());

        for (ScrAddedImage image : addedGraphics.getImages()) {
            this.imageTypes.add(image.getType());
        }

        for (ScrAddedSprite sprite : addedGraphics.getSprites()) {
            this.spriteTypes.add(sprite.getType());
        }

        for (ScrAddedFlingy entry : addedGraphics.getFlingies()) {
            this.flingyTypes.add(entry.getType());
        }

        this.weaponTypes = new ArrayList<>(ScrWeapons.getTypes());

        for (ScrAddedWeapon weapon : addedUnits.getWeapons()) {
            this.weaponTypes.add(weapon.getType());
        }
    }

    /**
     * Registers the content: the player count, the resource kinds, the races, then sounds, images, sprites, flingies,
     * upgrades, techs, weapons, orders and units (the native ones, then the added ones), each type with its definition, the extension columns of
     * each table and the unit trait columns. The added unit types and graphics are closed first.
     *
     * @param content the open content registry
     * @throws IllegalArgumentException if a DAT reference lies outside its table or an identity is registered at another index
     * @throws IllegalStateException    if the registration is closed or an identity already has another index
     */
    public void register(@NotNull ContentRegistry content) {
        this.addedUnits.freeze();
        this.addedGraphics.freeze();

        content.setPlayerCount(PLAYER_COUNT);

        for (int index = 0; index < ScrResources.getTypes().size(); index++) {
            requireNativeOrder(content.getResourceKinds().register(ScrResources.getTypes().get(index)), index);
        }

        for (int index = 0; index < ScrDamageKinds.getTypes().size(); index++) {
            requireNativeOrder(content.getDamageKinds().register(ScrDamageKinds.getTypes().get(index)), index);
        }

        this.registerRaces(content.getFactions());
        this.registerSounds(content.getSoundTypes());
        this.registerImages(content.getImageTypes());
        this.registerSprites(content.getSpriteTypes());
        this.registerFlingies(content.getMotionProfiles());
        this.registerUpgrades(content.getUpgradeTypes());
        this.registerTechs(content.getTechTypes());
        this.registerWeapons(content.getWeaponTypes());
        this.registerOrders(content.getOrderTypes());
        this.registerUnits(content.getUnitTypes());

        List<ToIntFunction<String>> addedImages = new ArrayList<>();
        List<ToIntFunction<String>> addedSprites = new ArrayList<>();
        List<ToIntFunction<String>> addedFlingies = new ArrayList<>();
        List<ToIntFunction<String>> addedUnitTypes = new ArrayList<>();
        List<ToIntFunction<String>> addedWeapons = new ArrayList<>();

        for (ScrAddedImage image : this.addedGraphics.getImages()) {
            addedImages.add(image::getValue);
        }

        for (ScrAddedSprite sprite : this.addedGraphics.getSprites()) {
            addedSprites.add(sprite::getValue);
        }

        for (ScrAddedFlingy flingy : this.addedGraphics.getFlingies()) {
            addedFlingies.add(flingy::getValue);
        }

        for (ScrAddedUnitType type : this.addedUnits.getTypes()) {
            addedUnitTypes.add(type::getValue);
        }

        for (ScrAddedWeapon weapon : this.addedUnits.getWeapons()) {
            addedWeapons.add(weapon::getValue);
        }

        this.registerExtensionColumns(content, ScrDatKind.SOUNDS, this.sfxdata, ScrSounds.COUNT, List.of());
        this.registerExtensionColumns(content, ScrDatKind.IMAGES, this.images, ScrImages.COUNT, addedImages);
        this.registerExtensionColumns(content, ScrDatKind.SPRITES, this.sprites, ScrSprites.COUNT, addedSprites);
        this.registerExtensionColumns(content, ScrDatKind.FLINGY, this.flingy, ScrFlingies.COUNT, addedFlingies);
        this.registerExtensionColumns(content, ScrDatKind.UPGRADES, this.upgrades, ScrUpgrades.COUNT, List.of());
        this.registerExtensionColumns(content, ScrDatKind.TECHDATA, this.techdata, ScrTechs.COUNT, List.of());
        this.registerExtensionColumns(content, ScrDatKind.WEAPONS, this.weapons, ScrWeapons.COUNT, addedWeapons);
        this.registerExtensionColumns(content, ScrDatKind.ORDERS, this.orders, ScrOrders.COUNT, List.of());
        this.registerExtensionColumns(content, ScrDatKind.UNITS, this.units, ScrUnits.COUNT, addedUnitTypes);

        List<ScrAddedUnitType> added = this.addedUnits.getTypes();

        for (ScrUnitTypeView.TraitColumn column : ScrUnitTypeView.TraitColumn.values()) {
            int[] values = ScrUnitTraits.createValues(column);

            content.addColumn(ScrDatKind.UNITS.getTableKind(), ScrUnitTraits.getKey(column), (IntColumn traits) -> {
                for (int type = 0; type < values.length; type++) {
                    traits.set(type, values[type]);
                }

                for (int index = 0; index < added.size(); index++) {
                    traits.set(ScrUnits.COUNT + index, added.get(index).getTrait(column));
                }
            });
        }
    }

    private static void requireNativeOrder(@NotNull ContentType type, int nativeId) {
        if (type.getIndex() != nativeId) {
            throw Exceptions.newIllegalArgumentException("The type {0} is registered at {1}, not at its native entry {2}.", type, type.getIndex(),
                                                         nativeId);
        }
    }

    /**
     * Returns the identity a DAT reference names.
     *
     * @param <T>        the identity type
     * @param types      the identities of the referenced kind in native order
     * @param nativeNone the native "none" entry of the kind, or {@link #NO_NATIVE_NONE}
     * @param value      the DAT value
     * @param field      the DAT field, for the failure message
     * @param type       the entry that holds the reference, for the failure message
     * @return the identity, or {@code null} for the "none" entry
     * @throws IllegalArgumentException if the value is outside the referenced table
     */
    private static <T extends ContentType> @Nullable T resolveReference(@NotNull List<@NotNull T> types, int nativeNone, int value,
                                                                        @NotNull String field, int type) {
        if (value == nativeNone) {
            return null;
        }

        if (value < 0 || value >= types.size()) {
            throw Exceptions.newIllegalArgumentException("The {0} of entry {1} refers to {2}, outside its table.", field, type, value);
        }

        return types.get(value);
    }

    private static @Nullable OrderType resolveUnitOrder(@NotNull ToIntFunction<String> values, @NotNull String field, int type) {
        return resolveReference(ScrOrders.getTypes(), ScrOrders.NATIVE_NONE, values.applyAsInt(field), field, type);
    }

    private @NotNull AnimationSet resolveAnimationSet(int setIndex, int type) {
        if (setIndex < 0 || setIndex >= this.animations.getSets().size()) {
            throw Exceptions.newIllegalArgumentException("The {0} of entry {1} refers to {2}, outside the animation library.",
                                                         ImagesDat.ISCRIPT, type, setIndex);
        }

        return this.animations.getSet(setIndex);
    }

    private static @Nullable Faction resolveRace(int raceIndex) {
        return raceIndex == ContentType.NO_INDEX ? null : ScrRaces.get(raceIndex);
    }

    private static @NotNull ResourceCost createCost(int minerals, int gas) {
        return new ResourceCost.Builder().add(ScrResources.MINERALS, minerals).add(ScrResources.GAS, gas).build();
    }

    /**
     * Reads the value of a type from a DAT field whose first element may belong to a later type.
     *
     * @param kind  the DAT table kind
     * @param table the DAT table
     * @param field the field
     * @param type  the type
     * @return the value, or {@code 0} for a type before the first or after the last element of the field
     */
    private static int read(@NotNull ScrDatKind kind, @NotNull DatTable table, @NotNull String field, int type) {
        int element = type - kind.getFirstType(field);

        return element >= 0 && element < table.getCount(field) ? table.get(field, element) : 0;
    }

    private void registerRaces(@NotNull DefinitionRegistry<Faction, FactionDefinition> races) {
        for (int nativeId = 0; nativeId < ScrRaces.COUNT; nativeId++) {
            requireNativeOrder(races.register(ScrRaces.get(nativeId), ScrRaces.getDefinition(nativeId)), nativeId);
        }
    }

    private void registerSounds(@NotNull DefinitionRegistry<SoundType, SoundDefinition> sounds) {
        for (int type = 0; type < ScrSounds.COUNT; type++) {
            SoundDefinition definition = new SoundDefinition.Builder().setPriority(this.sfxdata.get(SfxdataDat.PRIORITY, type))
                                                                      .setVolume(this.sfxdata.get(SfxdataDat.VOLUME, type))
                                                                      .build();

            requireNativeOrder(sounds.register(ScrSounds.get(type), definition), type);
        }
    }

    private void registerImages(@NotNull DefinitionRegistry<ImageType, ImageDefinition> images) {
        for (int type = 0; type < ScrImages.COUNT; type++) {
            int nativeId = type;

            requireNativeOrder(images.register(ScrImages.get(type), this.createImageDefinition(field -> this.images.get(field, nativeId), type)),
                               type);
        }

        List<ScrAddedImage> added = this.addedGraphics.getImages();

        for (int index = 0; index < added.size(); index++) {
            int id = ScrAddedGraphics.FIRST_IMAGE_ID + index;

            requireNativeOrder(images.register(added.get(index).getType(), this.createImageDefinition(added.get(index)::getValue, id)), id);
        }
    }

    /**
     * Creates the engine definition of an image from its {@code images.dat} values.
     *
     * @param values the value of each {@code images.dat} field of the image
     * @param type   the image id, for the failure messages
     * @return the created {@link ImageDefinition} instance
     * @throws IllegalArgumentException if the script lies outside the animation library
     */
    private @NotNull ImageDefinition createImageDefinition(@NotNull ToIntFunction<String> values, int type) {
        return new ImageDefinition.Builder().setAnimationSet(this.resolveAnimationSet(values.applyAsInt(ImagesDat.ISCRIPT), type))
                                            .setTurning(ScrDerivedValueUtils.isSet(values.applyAsInt(ImagesDat.GRAPHIC_TURNS)))
                                            .setClickable(ScrDerivedValueUtils.isSet(values.applyAsInt(ImagesDat.CLICKABLE)))
                                            .build();
    }

    private void registerSprites(@NotNull DefinitionRegistry<SpriteType, SpriteDefinition> sprites) {
        for (int type = 0; type < ScrSprites.COUNT; type++) {
            SpriteDefinition definition = new SpriteDefinition.Builder()
                    .setImage(resolveReference(this.imageTypes, NO_NATIVE_NONE, this.sprites.get(SpritesDat.IMAGE, type), SpritesDat.IMAGE, type))
                    .setSelectionSize(read(ScrDatKind.SPRITES, this.sprites, SpritesDat.SELECTION_CIRCLE, type))
                    .build();

            requireNativeOrder(sprites.register(ScrSprites.get(type), definition), type);
        }

        List<ScrAddedSprite> added = this.addedGraphics.getSprites();

        for (int index = 0; index < added.size(); index++) {
            ScrAddedSprite sprite = added.get(index);
            int id = ScrAddedGraphics.FIRST_SPRITE_ID + index;
            SpriteDefinition definition = new SpriteDefinition.Builder()
                    .setImage(resolveReference(this.imageTypes, NO_NATIVE_NONE, sprite.getValue(SpritesDat.IMAGE), SpritesDat.IMAGE, id))
                    .setSelectionSize(sprite.getValue(SpritesDat.SELECTION_CIRCLE))
                    .build();

            requireNativeOrder(sprites.register(sprite.getType(), definition), id);
        }
    }

    private void registerFlingies(@NotNull DefinitionRegistry<MotionProfile, MotionProfileDefinition> flingies) {
        for (int type = 0; type < ScrFlingies.COUNT; type++) {
            int nativeId = type;

            requireNativeOrder(flingies.register(ScrFlingies.get(type),
                                                 this.createFlingyDefinition(field -> this.flingy.get(field, nativeId), type, type)),
                               type);
        }

        List<ScrAddedFlingy> added = this.addedGraphics.getFlingies();

        for (int index = 0; index < added.size(); index++) {
            ScrAddedFlingy flingy = added.get(index);
            int id = ScrAddedGraphics.FIRST_FLINGY_ID + index;

            requireNativeOrder(flingies.register(flingy.getType(), this.createFlingyDefinition(flingy::getValue, id, flingy.getBaseFlingy())), id);
        }
    }

    /**
     * Creates the engine definition of a flingy entry from its {@code flingy.dat} values.
     *
     * @param values     the value of each {@code flingy.dat} field of the entry
     * @param type       the flingy id, for the failure messages
     * @param traitsType the native flingy entry whose traits the entry has: the entry itself, or the base of an added entry
     * @return the created {@link MotionProfileDefinition} instance
     * @throws IllegalArgumentException if the sprite lies outside its table
     */
    private @NotNull MotionProfileDefinition createFlingyDefinition(@NotNull ToIntFunction<String> values, int type, int traitsType) {
        return new MotionProfileDefinition.Builder()
                .setSprite(resolveReference(this.spriteTypes, NO_NATIVE_NONE, values.applyAsInt(FlingyDat.SPRITE), FlingyDat.SPRITE, type))
                .setTopSpeed(values.applyAsInt(FlingyDat.TOP_SPEED))
                .setAcceleration(values.applyAsInt(FlingyDat.ACCELERATION))
                .setStoppingDistance(values.applyAsInt(FlingyDat.HALT_DISTANCE))
                .setTurnRate(values.applyAsInt(FlingyDat.TURN_RATE))
                .setAcceleratingTurn(ScrFlingyTraits.isAcceleratingTurn(traitsType))
                .build();
    }

    private void registerUpgrades(@NotNull DefinitionRegistry<UpgradeType, UpgradeDefinition> upgrades) {
        for (int type = 0; type < ScrUpgrades.COUNT; type++) {
            UpgradeDefinition definition = new UpgradeDefinition.Builder()
                    .setFaction(resolveRace(ScrDerivedValueUtils.getRaceIndex(this.upgrades.get(UpgradesDat.RACE, type))))
                    .setBaseCost(createCost(this.upgrades.get(UpgradesDat.MINERAL_COST, type), this.upgrades.get(UpgradesDat.GAS_COST, type)))
                    .setCostFactor(createCost(this.upgrades.get(UpgradesDat.MINERAL_COST_FACTOR, type),
                                              this.upgrades.get(UpgradesDat.GAS_COST_FACTOR, type)))
                    .setBaseTime(this.upgrades.get(UpgradesDat.RESEARCH_TIME, type))
                    .setTimeFactor(this.upgrades.get(UpgradesDat.RESEARCH_TIME_FACTOR, type))
                    .setMaximumLevel(this.upgrades.get(UpgradesDat.MAXIMUM_LEVEL, type))
                    .build();

            requireNativeOrder(upgrades.register(ScrUpgrades.get(type), definition), type);
        }
    }

    private void registerTechs(@NotNull DefinitionRegistry<TechType, TechDefinition> techs) {
        for (int type = 0; type < ScrTechs.COUNT; type++) {
            TechDefinition definition = new TechDefinition.Builder()
                    .setFaction(resolveRace(ScrDerivedValueUtils.getRaceIndex(this.techdata.get(TechdataDat.RACE, type))))
                    .setCost(createCost(this.techdata.get(TechdataDat.MINERAL_COST, type), this.techdata.get(TechdataDat.GAS_COST, type)))
                    .setResearchTime(this.techdata.get(TechdataDat.RESEARCH_TIME, type))
                    .setEnergyCost(this.techdata.get(TechdataDat.ENERGY_COST, type))
                    .build();

            requireNativeOrder(techs.register(ScrTechs.get(type), definition), type);
        }
    }

    private void registerWeapons(@NotNull DefinitionRegistry<WeaponType, WeaponDefinition> weapons) {
        for (int type = 0; type < ScrWeapons.COUNT; type++) {
            int nativeId = type;

            requireNativeOrder(weapons.register(ScrWeapons.get(type), this.createWeaponDefinition(field -> this.weapons.get(field, nativeId), type)),
                               type);
        }

        List<ScrAddedWeapon> added = this.addedUnits.getWeapons();

        for (int index = 0; index < added.size(); index++) {
            ScrAddedWeapon weapon = added.get(index);
            int id = ScrAddedUnitTypes.FIRST_WEAPON_ID + index;

            requireNativeOrder(weapons.register(weapon.getType(), this.createWeaponDefinition(weapon::getValue, id)),
                               id - ScrAddedUnitTypes.WEAPON_CONTENT_INDEX_SHIFT);
        }
    }

    /**
     * Creates the engine definition of a weapon from its {@code weapons.dat} values.
     *
     * @param values the value of each {@code weapons.dat} field of the weapon
     * @param type   the {@code weapons.dat} id, for the failure messages
     * @return the created {@link WeaponDefinition} instance
     * @throws IllegalArgumentException if a reference lies outside its table
     */
    private @NotNull WeaponDefinition createWeaponDefinition(@NotNull ToIntFunction<String> values, int type) {
        int targets = ScrDerivedValueUtils.getWeaponTargets(values.applyAsInt(WeaponsDat.TARGET_FLAGS));
        WeaponDefinition.Builder definition = new WeaponDefinition.Builder()
                .setDamage(values.applyAsInt(WeaponsDat.DAMAGE_AMOUNT))
                .setDamageBonus(values.applyAsInt(WeaponsDat.DAMAGE_BONUS))
                .setDamageFactor(values.applyAsInt(WeaponsDat.DAMAGE_FACTOR))
                .setDamageKind(resolveReference(ScrDamageKinds.getTypes(), NO_NATIVE_NONE, values.applyAsInt(WeaponsDat.DAMAGE_TYPE),
                                                WeaponsDat.DAMAGE_TYPE, type))
                .setUpgrade(resolveReference(ScrUpgrades.getTypes(), ScrUpgrades.NATIVE_NONE, values.applyAsInt(WeaponsDat.DAMAGE_UPGRADE),
                                             WeaponsDat.DAMAGE_UPGRADE, type))
                .setCooldown(values.applyAsInt(WeaponsDat.COOLDOWN))
                .setMinimumRange(values.applyAsInt(WeaponsDat.MINIMUM_RANGE))
                .setMaximumRange(values.applyAsInt(WeaponsDat.MAXIMUM_RANGE))
                .setProjectile(resolveReference(this.flingyTypes, NO_NATIVE_NONE, values.applyAsInt(WeaponsDat.FLINGY_TYPE),
                                                WeaponsDat.FLINGY_TYPE, type))
                .setSplashInnerRadius(values.applyAsInt(WeaponsDat.INNER_SPLASH_RADIUS))
                .setSplashMiddleRadius(values.applyAsInt(WeaponsDat.MEDIUM_SPLASH_RADIUS))
                .setSplashOuterRadius(values.applyAsInt(WeaponsDat.OUTER_SPLASH_RADIUS));

        for (WeaponTarget target : WeaponTarget.values()) {
            if ((targets & target.getMask()) != 0) {
                definition.addTarget(target);
            }
        }

        return definition.build();
    }

    /**
     * Returns the weapon a {@code units.dat} weapon value names: none, a native weapon or an added one ({@link ScrAddedUnitTypes#FIRST_WEAPON_ID}).
     *
     * @param value the weapon value
     * @param field the {@code units.dat} field, for the failure message
     * @param type  the unit type that holds the reference, for the failure message
     * @return the weapon, or {@code null} for none
     * @throws IllegalArgumentException if the value names no weapon
     */
    private @Nullable WeaponType resolveWeapon(int value, @NotNull String field, int type) {
        int index = value > ScrWeapons.NATIVE_NONE ? value - ScrAddedUnitTypes.WEAPON_CONTENT_INDEX_SHIFT : value;

        return value == ScrWeapons.NATIVE_NONE ? null : resolveReference(this.weaponTypes, NO_NATIVE_NONE, index, field, type);
    }

    private void registerOrders(@NotNull DefinitionRegistry<OrderType, OrderDefinition> orders) {
        for (int type = 0; type < ScrOrders.COUNT; type++) {
            OrderDefinition definition = new OrderDefinition.Builder()
                    .setWeapon(resolveReference(ScrWeapons.getTypes(), ScrWeapons.NATIVE_NONE, this.orders.get(OrdersDat.WEAPON, type),
                                                OrdersDat.WEAPON, type))
                    .setTech(resolveReference(ScrTechs.getTypes(), ScrTechs.NATIVE_NONE, this.orders.get(OrdersDat.TECHNOLOGY, type),
                                              OrdersDat.TECHNOLOGY, type))
                    .setInterruptible(ScrDerivedValueUtils.isSet(this.orders.get(OrdersDat.CAN_BE_INTERRUPTED, type)))
                    .setQueueable(ScrDerivedValueUtils.isSet(this.orders.get(OrdersDat.CAN_BE_QUEUED, type)))
                    .build();

            requireNativeOrder(orders.register(ScrOrders.get(type), definition), type);
        }
    }

    private void registerUnits(@NotNull DefinitionRegistry<UnitType, UnitDefinition> unitTypes) {
        DatTable table = this.units;

        for (int type = 0; type < ScrUnits.COUNT; type++) {
            int nativeId = type;

            requireNativeOrder(unitTypes.register(ScrUnits.get(type), this.createUnitDefinition(field -> table.get(field, nativeId), type)), type);
        }

        for (ScrAddedUnitType added : this.addedUnits.getTypes()) {
            int id = this.addedUnits.getId(added.getType());

            requireNativeOrder(unitTypes.register(added.getType(), this.createUnitDefinition(added::getValue, id)),
                               ScrAddedUnitTypes.toContentIndex(id));
        }
    }

    /**
     * Creates the engine definition of a unit type from its {@code units.dat} values.
     *
     * @param values the value of each {@code units.dat} field of the type
     * @param type   the SC:R unit type id, for the failure messages
     * @return the created {@link UnitDefinition} instance
     * @throws IllegalArgumentException if a reference lies outside its table
     */
    private @NotNull UnitDefinition createUnitDefinition(@NotNull ToIntFunction<String> values, int type) {
        int traits = ScrDerivedValueUtils.getUnitTraits(values.applyAsInt(UnitsDat.FLAGS));
        UnitDefinition.Builder definition = new UnitDefinition.Builder()
                .setFaction(resolveRace(ScrDerivedValueUtils.getUnitRaceIndex(values.applyAsInt(UnitsDat.GROUP_FLAGS))))
                .setMotionProfile(resolveReference(this.flingyTypes, NO_NATIVE_NONE, values.applyAsInt(UnitsDat.FLINGY_TYPE),
                                                   UnitsDat.FLINGY_TYPE, type))
                .setHitPoints(values.applyAsInt(UnitsDat.HIT_POINTS))
                .setArmor(values.applyAsInt(UnitsDat.ARMOR))
                .setArmorUpgrade(resolveReference(ScrUpgrades.getTypes(), ScrUpgrades.NATIVE_NONE, values.applyAsInt(UnitsDat.ARMOR_UPGRADE),
                                                  UnitsDat.ARMOR_UPGRADE, type))
                .setSightRange(values.applyAsInt(UnitsDat.SIGHT_RANGE))
                .setTargetAcquisitionRange(values.applyAsInt(UnitsDat.TARGET_ACQUISITION_RANGE))
                .setGroundWeapon(this.resolveWeapon(values.applyAsInt(UnitsDat.GROUND_WEAPON), UnitsDat.GROUND_WEAPON, type))
                .setAirWeapon(this.resolveWeapon(values.applyAsInt(UnitsDat.AIR_WEAPON), UnitsDat.AIR_WEAPON, type))
                .setCost(createCost(values.applyAsInt(UnitsDat.MINERAL_COST), values.applyAsInt(UnitsDat.GAS_COST)))
                .setBuildTime(values.applyAsInt(UnitsDat.BUILD_TIME))
                .setSupplyProvided(values.applyAsInt(UnitsDat.SUPPLY_PROVIDED))
                .setSupplyRequired(values.applyAsInt(UnitsDat.SUPPLY_REQUIRED))
                .setSpaceRequired(values.applyAsInt(UnitsDat.SPACE_REQUIRED))
                .setSpaceProvided(values.applyAsInt(UnitsDat.SPACE_PROVIDED))
                .setPlacementWidth(values.applyAsInt(UnitsDat.PLACEMENT_WIDTH))
                .setPlacementHeight(values.applyAsInt(UnitsDat.PLACEMENT_HEIGHT))
                .setCollisionLeft(values.applyAsInt(UnitsDat.DIMENSION_LEFT))
                .setCollisionUp(values.applyAsInt(UnitsDat.DIMENSION_UP))
                .setCollisionRight(values.applyAsInt(UnitsDat.DIMENSION_RIGHT))
                .setCollisionDown(values.applyAsInt(UnitsDat.DIMENSION_DOWN))
                .setIdleOrder(ControllerKind.COMPUTER, resolveUnitOrder(values, UnitsDat.COMPUTER_IDLE_ORDER, type))
                .setIdleOrder(ControllerKind.HUMAN, resolveUnitOrder(values, UnitsDat.HUMAN_IDLE_ORDER, type));

        for (UnitTrait trait : UnitTrait.values()) {
            if ((traits & trait.getMask()) != 0) {
                definition.addTrait(trait);
            }
        }

        return definition.build();
    }

    /**
     * Adds the extension columns of one DAT table, in DAT field order; each starts as a copy of the field's values taken
     * now, at element {@code e} for type {@code e + kind.getFirstType(field)}, and then holds the value of each added entry of the kind after the
     * native entries.
     *
     * @param content     the open content registry
     * @param kind        the DAT table kind
     * @param table       the DAT table
     * @param nativeCount the number of native entries of the kind, the content index of the first added entry
     * @param added       the value of each field of each added entry, in registration order
     */
    private void registerExtensionColumns(@NotNull ContentRegistry content, @NotNull ScrDatKind kind, @NotNull DatTable table, int nativeCount,
                                          @NotNull List<@NotNull ToIntFunction<String>> added) {
        for (String field : kind.getExtensionFields()) {
            int firstType = kind.getFirstType(field);
            int[] values = table.copyColumn(field);

            content.addColumn(kind.getTableKind(), kind.getExtensionKey(field), (IntColumn column) -> {
                for (int element = 0; element < values.length; element++) {
                    column.set(element + firstType, values[element]);
                }

                for (int index = 0; index < added.size(); index++) {
                    column.set(nativeCount + index, added.get(index).applyAsInt(field));
                }
            });
        }
    }
}
