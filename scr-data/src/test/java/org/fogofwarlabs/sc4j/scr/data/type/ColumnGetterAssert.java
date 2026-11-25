package org.fogofwarlabs.sc4j.scr.data.type;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatSchema;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Asserts that every single-id getter of a typed table returns the same value (width, sign and table quirks) or exception as the {@link DatTable}
 * read of the same column and id, for every id of every column and the ids just outside it.
 */
final class ColumnGetterAssert {
    private static final @NotNull String UNUSED_PREFIX = "UNUSED";
    private static final @NotNull String DAT_DIRECTORY = "arr/";
    /**
     * The unit getter of the build-time lookup, which reads the requirement index column from type {@code 228} on.
     */
    private static final @NotNull String BUILD_TIME_LOOKUP = "getBuildTime";
    private static final int PROBE_LENGTH = 2048;
    /**
     * The value of every element of a probe trait column, which no DAT probe column holds.
     */
    private static final int NO_DAT_COLUMN = -1;
    /**
     * The id the probe tables read: past the offset of every partial column, below the first unit type whose build time the requirement index column
     * holds.
     */
    private static final int PROBE_ID = 200;
    private static final int PROBE_SHIFT = 16;
    private static final int PROBE_MASK = 0xFFFF;
    private static final int OUTSIDE_IDS = 3;
    /**
     * The seeds of the generated contents; the zero seed stands for a zero-filled content.
     */
    private static final long @NotNull [] SEEDS = {0L, 1L, 0x5C4AL};

    /**
     * The column and index offset a typed getter reads.
     */
    private static final class Binding {
        private final @NotNull Method getter;
        private final @NotNull String columnName;
        private final int offset;

        private Binding(@NotNull Method getter, @NotNull String columnName, int offset) {
            this.getter     = getter;
            this.columnName = columnName;
            this.offset     = offset;
        }

        private @NotNull Method getGetter() {
            return this.getter;
        }

        private @NotNull String getColumnName() {
            return this.columnName;
        }

        private int getOffset() {
            return this.offset;
        }
    }

    /**
     * Asserts the getters of a typed table over a zero-filled content and over contents of random bytes.
     *
     * @param fileName       the DAT file name, which the failure messages name
     * @param schemaFactory  the schema factory of the loader
     * @param tableFactory   the typed table factory of the loader
     * @param loaderClass    the loader class, whose column constants name the columns
     * @param typeTableClass the typed table class
     * @throws Exception if a table cannot be parsed or a getter cannot be bound
     */
    static void assertGeneratedTables(@NotNull String fileName,
                                      @NotNull Supplier<DatSchema> schemaFactory, @NotNull Function<DatTable, Object> tableFactory,
                                      @NotNull Class<?> loaderClass, @NotNull Class<?> typeTableClass) throws Exception {
        for (long seed : SEEDS) {
            byte[] content = new byte[schemaFactory.get().getExpectedSize()];

            if (seed != 0L) {
                new Random(seed).nextBytes(content);
            }

            compareTables(fileName, schemaFactory.get().parse(content), tableFactory, loaderClass, typeTableClass);
        }
    }

    /**
     * Asserts the getters of a typed table over the DAT file of the installation.
     *
     * @param fileName       the DAT file name in the {@code arr} directory
     * @param schemaFactory  the schema factory of the loader
     * @param tableFactory   the typed table factory of the loader
     * @param loaderClass    the loader class, whose column constants name the columns
     * @param typeTableClass the typed table class
     * @throws Exception if the file cannot be read or parsed or a getter cannot be bound
     */
    static void assertInstalledTable(@NotNull String fileName,
                                     @NotNull Supplier<DatSchema> schemaFactory, @NotNull Function<DatTable, Object> tableFactory,
                                     @NotNull Class<?> loaderClass, @NotNull Class<?> typeTableClass) throws Exception {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            compareTables(fileName, schemaFactory.get().parse(storage.readFile(DAT_DIRECTORY + fileName)), tableFactory, loaderClass, typeTableClass);
        }
    }

    private static void compareTables(@NotNull String fileName, @NotNull DatTable table,
                                      @NotNull Function<DatTable, Object> tableFactory,
                                      @NotNull Class<?> loaderClass, @NotNull Class<?> typeTableClass) throws Exception {
        Object typed = tableFactory.apply(table);
        List<Binding> bindings = createBindings(fileName, loaderClass, typeTableClass);
        Set<String> boundColumns = new HashSet<>();

        for (Binding binding : bindings) {
            int count = table.getCount(binding.getColumnName());

            boundColumns.add(binding.getColumnName());

            for (int identifier = binding.getOffset() - OUTSIDE_IDS; identifier < binding.getOffset() + count + OUTSIDE_IDS; identifier++) {
                int typedIdentifier = identifier;
                Object expected = readExpectedValue(table, binding, identifier);
                Object actual = getReadOutcome(() -> readTypedValue(binding.getGetter(), typed, typedIdentifier));

                Assertions.assertEquals(expected, actual, fileName + " " + binding.getGetter().getName() + " id " + identifier);
            }
        }

        for (Field constant : loaderClass.getFields()) {
            if (constant.getType() == String.class && !constant.getName().startsWith(UNUSED_PREFIX)) {
                Assertions.assertTrue(boundColumns.contains((String) constant.get(null)), fileName + " has no getter for " + constant.getName());
            }
        }

        Assertions.assertEquals(table.getCount((String) loaderClass.getField(getColumnConstants(typeTableClass).getFirst()).get(null)),
                                typeTableClass.getMethod("getCount").invoke(typed));
    }

    /**
     * Finds the column and offset of every single-id getter by reading a probe table whose arrays hold their column and element index.
     * <p>
     * The getters are bound to their columns by name: each {@code Column} constant of a typed table names the column constant of the same name in
     * its loader class.
     */
    private static @NotNull List<Binding> createBindings(@NotNull String fileName,
                                                         @NotNull Class<?> loaderClass, @NotNull Class<?> typeTableClass) throws Exception {
        List<String> constants = getColumnConstants(typeTableClass);
        Function<Enum<?>, DatColumn> probe = column -> {
            int[] values = new int[PROBE_LENGTH];

            for (int index = 0; index < values.length; index++) {
                values[index] = (column.ordinal() + 1) << PROBE_SHIFT | index;
            }

            return new ArrayDatColumn(values);
        };

        // The tables with requirement index columns also take the loader's column names (strings) after the columns, the
        // tables with a game memory layout an overrun reader, which the probe ids inside the columns never reach, and the
        // unit table its trait columns, which hold NO_DAT_COLUMN here: a trait getter reads no DAT column. The table's shortest constructor is used,
        // since the order of getConstructors is not specified.
        Constructor<?> constructor = Arrays.stream(typeTableClass.getConstructors())
                                           .min(Comparator.comparingInt(Constructor::getParameterCount))
                                           .orElseThrow();
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        ColumnOverrunReader<Object> unreachable = (column, index) -> Assertions.fail("The probe read " + column + " outside its column.");

        arguments[0] = probe;

        for (int parameter = 1; parameter < parameterTypes.length; parameter++) {
            if (parameterTypes[parameter] == Function.class) {
                arguments[parameter] = (Function<Enum<?>, DatColumn>) column -> {
                    int[] values = new int[PROBE_LENGTH];

                    Arrays.fill(values, NO_DAT_COLUMN);

                    return new ArrayDatColumn(values);
                };
            } else {
                arguments[parameter] = parameterTypes[parameter] == ColumnOverrunReader.class ? unreachable : "probe";
            }
        }

        Object probeTable = constructor.newInstance(arguments);
        List<Binding> bindings = new ArrayList<>();

        for (Method method : typeTableClass.getMethods()) {
            if (method.getReturnType() != int.class || method.getParameterCount() != 1 || method.getParameterTypes()[0] != int.class) {
                continue;
            }

            int value = (int) method.invoke(probeTable, PROBE_ID);

            // A trait getter, and a getter that reads no column at all (the base type of a native id is the id itself), binds no DAT column.
            if (value == NO_DAT_COLUMN || value >>> PROBE_SHIFT == 0) {
                continue;
            }

            String constant = constants.get((value >>> PROBE_SHIFT) - 1);

            bindings.add(new Binding(method, (String) loaderClass.getField(constant).get(null), PROBE_ID - (value & PROBE_MASK)));
        }

        long boundColumnCount = bindings.stream().map(Binding::getColumnName).distinct().count();

        Assertions.assertEquals(constants.size(), boundColumnCount, fileName + " getters");

        return bindings;
    }

    private static @NotNull List<String> getColumnConstants(@NotNull Class<?> typeTableClass) throws ClassNotFoundException {
        Class<?> columnClass = Class.forName(typeTableClass.getName() + "$Column");
        List<String> names = new ArrayList<>();

        for (Object constant : columnClass.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }

        return names;
    }

    /**
     * Reads the expected outcome of a typed getter: the name lookup inside the column, the DAT memory read
     * ({@link DatTable#readNativeElement(String, int)}) outside it, which the typed tables reach through their {@link ColumnOverrunReader}, and for
     * the unit build-time lookup from type {@code 228} on the requirement index column.
     *
     * @param table      the parsed table
     * @param binding    the getter and its column
     * @param identifier the id the getter reads
     * @return the value, or the class name of the exception
     */
    private static @NotNull Object readExpectedValue(@NotNull DatTable table, @NotNull Binding binding, int identifier) {
        String column = binding.getColumnName();
        int elementIndex = identifier - binding.getOffset();

        if (binding.getGetter().getName().equals(BUILD_TIME_LOOKUP) && identifier >= UnitsDat.UNIT_COUNT) {
            return getReadOutcome(() -> table.readNativeElement(UnitsDat.REQUIREMENT_INDEX, identifier - UnitsDat.UNIT_COUNT));
        }

        if (elementIndex < 0 || elementIndex >= table.getCount(column)) {
            return getReadOutcome(() -> table.readNativeElement(column, elementIndex));
        }

        return getReadOutcome(() -> table.get(column, elementIndex));
    }

    private static int readTypedValue(@NotNull Method getter, @NotNull Object table, int identifier) {
        try {
            return (int) getter.invoke(table, identifier);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }

            return Assertions.fail(exception);
        } catch (IllegalAccessException exception) {
            return Assertions.fail(exception);
        }
    }

    private static @NotNull Object getReadOutcome(@NotNull IntSupplier read) {
        try {
            return read.getAsInt();
        } catch (RuntimeException exception) {
            // HotSpot may throw a preallocated exception without message (OmitStackTraceInFastThrow), so only the class is compared.
            return exception.getClass().getName();
        }
    }

    /**
     * Creates a {@link ColumnGetterAssert} instance.
     */
    private ColumnGetterAssert() {
    }
}
