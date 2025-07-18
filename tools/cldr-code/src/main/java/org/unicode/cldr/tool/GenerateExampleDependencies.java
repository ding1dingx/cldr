package org.unicode.cldr.tool;

import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.unicode.cldr.draft.FileUtilities;
import org.unicode.cldr.test.ExampleGenerator;
import org.unicode.cldr.util.CLDRConfig;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRPaths;
import org.unicode.cldr.util.CLDRTool;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.PathStarrer;
import org.unicode.cldr.util.RecordingCLDRFile;
import org.unicode.cldr.util.XMLSource;

@CLDRTool(alias = "generate-example-dependencies", description = "Generate example dependencies")
public class GenerateExampleDependencies {
    private static final String OUTPUT_FILE_NAME = "ExampleDependencies.java";
    private static final String DO_NOT_EDIT =
            "/* DO NOT EDIT THIS FILE, instead regenerate it using "
                    + GenerateExampleDependencies.class.getSimpleName()
                    + ".java */";
    private final CLDRFile englishFile;
    private final Factory factory;
    private final Set<String> locales;
    private final String outputDir;

    public static void main(String[] args) throws IOException {
        new GenerateExampleDependencies().run();
    }

    /**
     * Find dependencies where changing the value of one path changes example-generation for another
     * path.
     *
     * <p>The goal is to optimize example caching by only regenerating examples when necessary.
     */
    public GenerateExampleDependencies() {
        CLDRConfig info = CLDRConfig.getInstance();
        englishFile = info.getEnglish();
        factory = info.getCldrFactory();
        locales = factory.getAvailable();
        outputDir = CLDRPaths.GEN_DIRECTORY + "test" + File.separator;
    }

    public void run() throws IOException {
        int localeCount = locales.size();
        System.out.println(
                "Looping through " + localeCount + " locales ... (this may take an hour)");
        final Multimap<String, String> dependencies = TreeMultimap.create();
        int i = 0;
        for (String localeId : locales) {
            int percent = (++i * 100) / localeCount;
            System.out.println(localeId + " " + i + "/" + localeCount + " " + percent + "%");
            addDependenciesForLocale(dependencies, localeId);
        }
        System.out.println("Creating " + outputDir + OUTPUT_FILE_NAME + " ...");
        PrintWriter writer = FileUtilities.openUTF8Writer(outputDir, OUTPUT_FILE_NAME);
        int dependenciesWritten = writeDependenciesToFile(dependencies, writer);
        System.out.println(
                "Wrote "
                        + dependenciesWritten
                        + " dependencies to "
                        + outputDir
                        + GenerateExampleDependencies.OUTPUT_FILE_NAME);
        System.out.println(
                "If it looks OK, you can move it to the proper location, replacing the old version.");
    }

    private void addDependenciesForLocale(Multimap<String, String> dependencies, String localeId) {
        RecordingCLDRFile cldrFile = makeRecordingCldrFile(localeId);
        cldrFile.disableCaching();

        Set<String> paths = new TreeSet<>(cldrFile.getComparator());
        cldrFile.forEach(paths::add); // time-consuming

        ExampleGenerator egTest = new ExampleGenerator(cldrFile, englishFile);
        // Caching MUST be disabled for egTest.ICUServiceBuilder to prevent some dependencies from
        // being missed
        egTest.setCachingEnabled(false);

        for (String pathB : paths) {
            if (skipPathForDependencies(pathB)) {
                continue;
            }
            String valueB = cldrFile.getStringValue(pathB);
            if (valueB == null) {
                continue;
            }

            String starredB = PathStarrer.get(pathB);
            cldrFile.enableRecording();
            egTest.getExampleHtml(pathB, valueB);
            HashSet<String> pathsA = cldrFile.getRecordedPaths();
            cldrFile.clearRecordedPaths();
            cldrFile.disableRecording();
            for (String pathA : pathsA) {
                if (pathA.equals(pathB) || skipPathForDependencies(pathA)) {
                    continue;
                }
                String starredA = PathStarrer.get(pathA);
                dependencies.put(starredA, starredB);
            }
        }
    }

    private RecordingCLDRFile makeRecordingCldrFile(String localeId) {
        XMLSource topSource = factory.makeSource(localeId);
        List<XMLSource> parents = getParentSources(factory, localeId);
        XMLSource[] a = new XMLSource[parents.size()];
        return new RecordingCLDRFile(topSource, parents.toArray(a));
    }

    /**
     * Get the parent sources for the given localeId
     *
     * @param factory the Factory for makeSource
     * @param localeId the locale ID
     * @return the List of XMLSource objects
     */
    private static List<XMLSource> getParentSources(Factory factory, String localeId) {
        List<XMLSource> parents = new ArrayList<>();
        for (String currentLocaleID = LocaleIDParser.getParent(localeId);
                currentLocaleID != null;
                currentLocaleID = LocaleIDParser.getParent(currentLocaleID)) {
            parents.add(factory.makeSource(currentLocaleID));
        }
        return parents;
    }

    /**
     * Should the given path be skipped when testing example-generator path dependencies?
     *
     * @param path the path in question
     * @return true to skip, else false
     */
    private static boolean skipPathForDependencies(String path) {
        return path.endsWith("/alias") || path.startsWith("//ldml/identity");
    }

    /**
     * Write the given map of example-generator path dependencies to a java file.
     *
     * @param dependencies the multimap of example-generator path dependencies
     * @param writer the PrintWriter
     * @return the number of dependencies written
     */
    private int writeDependenciesToFile(Multimap<String, String> dependencies, PrintWriter writer) {
        writer.println("package org.unicode.cldr.test;");
        writer.println(DO_NOT_EDIT);
        writer.println("import com.google.common.collect.ImmutableSetMultimap;");
        writer.println("");
        writer.println("public class ExampleDependencies {");
        writer.println("    public static ImmutableSetMultimap<String, String> dependencies =");
        writer.println("            new ImmutableSetMultimap.Builder<String, String>()");
        int dependenciesWritten = 0;
        ArrayList<String> listA = new ArrayList<>(dependencies.keySet());
        Collections.sort(listA);
        for (String pathA : listA) {
            ArrayList<String> listB = new ArrayList<>(dependencies.get(pathA));
            Collections.sort(listB);
            String a = "\"" + pathA.replaceAll("\"", "\\\\\"") + "\"";
            writer.println("                    .putAll(" + a + ",");
            int remainingCount = listB.size();
            for (String pathB : listB) {
                String b = "\"" + pathB.replaceAll("\"", "\\\\\"") + "\"";
                String endOfLine = --remainingCount > 0 ? "," : ")";
                writer.println("                            " + b + endOfLine);
                ++dependenciesWritten;
            }
        }
        writer.println("                    .build();");
        writer.println("}");
        writer.println(DO_NOT_EDIT);
        writer.close();
        return dependenciesWritten;
    }
}
