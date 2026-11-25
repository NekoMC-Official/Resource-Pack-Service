package moe.nekomc.resourcepack.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CommunitySelectorTest {

    @Test
    void officialPackRequiresInstalledPlugin() {
        List<CommunityPack> packs = List.of(pack("companion", "Companion", "official", 0));

        CommunitySelection missing = select(packs, selected("companion"), context(name -> false));
        assertTrue(selectedIds(missing).isEmpty());
        assertTrue(skipReason(missing, "companion").contains("Companion"));

        CommunitySelection installed = select(packs, selected("companion"), context(name -> true));
        assertEquals(List.of("companion"), selectedIds(installed));
    }

    @Test
    void addonRequiresOfficialOfSamePlugin() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            pack("companion-extra", "Companion", "addon", 0));

        CommunitySelection withOfficial = select(packs, selected("companion", "companion-extra"), context(name -> true));
        assertEquals(List.of("companion", "companion-extra"), selectedIds(withOfficial));

        CommunitySelection orphan = select(packs, selected("companion-extra"), context(name -> true));
        assertTrue(selectedIds(orphan).isEmpty());
        assertTrue(skipReason(orphan, "companion-extra").contains("official"));
    }

    @Test
    void integrationRequiresEveryPlugin() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            pack("extra", "Extra", "official", 0),
            pack("bundle", "Companion", "integration", 0, "Extra"));

        CommunitySelection complete = select(packs, selected("companion", "extra", "bundle"), context(name -> true));
        assertEquals(List.of("companion", "extra", "bundle"), selectedIds(complete));

        CommunitySelection missing = select(
            packs, selected("companion", "extra", "bundle"), context("Companion"::equals));
        assertFalse(selectedIds(missing).contains("bundle"));
        assertTrue(skipReason(missing, "bundle").contains("Extra"));
    }

    @Test
    void standaloneDoesNotNeedAnyPlugin() {
        List<CommunityPack> packs = List.of(pack("vanilla", "universal", "standalone", 0));
        CommunitySelection selection = select(packs, selected("vanilla"), context(name -> false));
        assertEquals(List.of("vanilla"), selectedIds(selection));
    }

    @Test
    void standaloneMustBindUniversal() {
        List<CommunityPack> packs = List.of(pack("vanilla", "Companion", "standalone", 0));
        CommunitySelection selection = select(packs, selected("vanilla"), context(name -> true));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "vanilla").contains("universal"));
    }

    @Test
    void standaloneStillChecksCompatibility() {
        CommunityPack wrongMinecraft = standalone("standalone-mc", ">=1.20 <1.21", "", 0, 0);
        CommunitySelection minecraft = select(
            List.of(wrongMinecraft), selected("standalone-mc"), context(name -> false));
        assertTrue(selectedIds(minecraft).isEmpty());
        assertTrue(skipReason(minecraft, "standalone-mc").contains("Minecraft"));

        CommunityPack wrongFormat = standalone("standalone-format", "", "", 15, 20);
        CommunitySelection format = select(
            List.of(wrongFormat), selected("standalone-format"), context(name -> false));
        assertTrue(selectedIds(format).isEmpty());
        assertTrue(skipReason(format, "standalone-format").contains("格式"));

        CommunityPack wrongRequires = standalone("standalone-requires", "", ">=2.0", 0, 0);
        CommunitySelection requires = select(
            List.of(wrongRequires),
            selected("standalone-requires"),
            context(name -> false, "26.3", 64, name -> null));
        assertTrue(selectedIds(requires).isEmpty());
        assertTrue(skipReason(requires, "standalone-requires").contains("requires"));

        CommunityPack compatible = standalone("standalone-ok", ">=26.0", ">=1.0", 60, 70);
        CommunitySelection ok = select(List.of(compatible), selected("standalone-ok"), context(name -> false));
        assertEquals(List.of("standalone-ok"), selectedIds(ok));
    }

    @Test
    void duplicateOfficialKeepsFirst() {
        List<CommunityPack> packs = List.of(
            pack("companion-a", "Companion", "official", 0),
            pack("companion-b", "Companion", "official", 0));
        CommunitySelection selection = select(packs, selected("companion-a", "companion-b"), context(name -> true));
        assertEquals(List.of("companion-a"), selectedIds(selection));
        assertFalse(skipReason(selection, "companion-b").isEmpty());
    }

    @Test
    void defaultWinsWithinOfficialGroup() {
        List<CommunityPack> packs = List.of(
            pack("companion-old", "Companion", "official", 0),
            officialDefault("companion", "Companion", 0));
        CommunitySelection selection = select(packs, selected("companion-old", "companion"), context(name -> true));
        assertEquals(List.of("companion"), selectedIds(selection));
        assertFalse(skipReason(selection, "companion-old").isEmpty());
    }

    @Test
    void officialMultipleDefaultsPickHighestPriority() {
        List<CommunityPack> packs = List.of(
            officialDefault("companion-low", "Companion", 10),
            officialDefault("companion-high", "Companion", 30));
        CommunitySelection selection = select(
            packs, selected("companion-low", "companion-high"), context(name -> true));

        assertEquals(List.of("companion-high"), selectedIds(selection));
        assertTrue(skipReason(selection, "companion-low").contains("priority"));
        assertTrue(skipReason(selection, "companion-low").contains("companion-high"));
    }

    @Test
    void officialMultipleDefaultsTieBreakById() {
        List<CommunityPack> packs = List.of(
            officialDefault("companion-b", "Companion", 5),
            officialDefault("companion-a", "Companion", 5));
        CommunitySelection selection = select(
            packs, selected("companion-b", "companion-a"), context(name -> true));

        assertEquals(List.of("companion-a"), selectedIds(selection));
    }

    @Test
    void nonOfficialMultipleDefaultsSkipWholeGroup() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            addonDefault("seasonal-a", "seasonal", 10),
            addonDefault("seasonal-b", "seasonal", 20));
        CommunitySelection selection = select(
            packs,
            selected("companion", "seasonal-a", "seasonal-b"),
            context(name -> true));

        assertEquals(List.of("companion"), selectedIds(selection));
        assertTrue(skipReason(selection, "seasonal-a").contains("多个 default"));
        assertTrue(skipReason(selection, "seasonal-b").contains("多个 default"));
    }

    @Test
    void minecraftRangeFiltersPack() {
        List<CommunityPack> packs = List.of(
            compat("companion", "Companion", "official", ">=1.20 <1.21", "", "", false, 0, 0));
        CommunitySelection selection = select(
            packs, selected("companion"), context(name -> true, "1.21", 64, name -> "1.0.0"));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "companion").contains("Minecraft"));
    }

    @Test
    void packFormatFiltersPack() {
        List<CommunityPack> packs = List.of(
            compat("companion", "Companion", "official", "", "", "", false, 15, 20));
        CommunitySelection selection = select(
            packs, selected("companion"), context(name -> true, "26.3", 22, name -> "1.0.0"));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "companion").contains("资源包格式"));
    }

    @Test
    void requiresPluginVersion() {
        List<CommunityPack> packs = List.of(
            compat("companion", "Companion", "official", "", ">=1.2 <2.0", "", false, 0, 0));
        CommunitySelection selection = select(
            packs, selected("companion"), context(name -> true, "26.3", 64, name -> "1.1.0"));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "companion").contains("不满足"));
    }

    @Test
    void missingPluginVersionSkipsRequiredPack() {
        List<CommunityPack> packs = List.of(
            compat("companion", "Companion", "official", "", ">=1.2", "", false, 0, 0));
        CommunitySelection selection = select(
            packs, selected("companion"), context(name -> true, "26.3", 64, name -> null));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "companion").contains("无法确认"));
    }

    @Test
    void variantGroupUsesDefault() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            compat("companion-summer", "Companion", "addon", "", "", "summer", true, 0, 0),
            compat("companion-summer-alt", "Companion", "addon", "", "", "summer", false, 0, 0));
        CommunitySelection selection = select(
            packs,
            selected("companion", "companion-summer", "companion-summer-alt"),
            context(name -> true));

        assertEquals(List.of("companion", "companion-summer"), selectedIds(selection));
        assertFalse(skipReason(selection, "companion-summer-alt").isEmpty());
    }

    @Test
    void addonsWithoutVariantStayIndependent() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            pack("companion-a", "Companion", "addon", 0),
            pack("companion-b", "Companion", "addon", 0));
        CommunitySelection selection = select(
            packs, selected("companion", "companion-a", "companion-b"), context(name -> true));
        assertEquals(List.of("companion", "companion-a", "companion-b"), selectedIds(selection));
    }

    @Test
    void unknownRelationIsSkipped() {
        List<CommunityPack> packs = List.of(pack("mystery", "Companion", "mystery", 0));
        CommunitySelection selection = select(packs, selected("mystery"), context(name -> true));
        assertTrue(selectedIds(selection).isEmpty());
        assertTrue(skipReason(selection, "mystery").contains("未知"));
    }

    @Test
    void emptySelectionMeansNothing() {
        List<CommunityPack> packs = List.of(
            pack("companion", "Companion", "official", 0),
            pack("companion-extra", "Companion", "addon", 0));
        CommunitySelection selection = CommunitySelector.select(packs, Set.of(), context(name -> true));
        assertTrue(selection.selected().isEmpty());
        assertTrue(selection.skipped().isEmpty());
    }

    private static CommunitySelection select(
        List<CommunityPack> packs, Set<String> selectedIds, SelectionContext context) {
        return CommunitySelector.select(packs, selectedIds, context);
    }

    private static Set<String> selected(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    private static List<String> selectedIds(CommunitySelection selection) {
        return selection.selected().stream().map(CommunityPack::id).toList();
    }

    private static String skipReason(CommunitySelection selection, String id) {
        return selection.skipped().stream()
            .filter(skip -> skip.id().equals(id))
            .map(CommunitySkip::reason)
            .findFirst()
            .orElse("");
    }

    private static SelectionContext context(Predicate<String> installed) {
        return context(installed, "26.3", 64, name -> "1.0.0");
    }

    private static SelectionContext context(
        Predicate<String> installed, String minecraft, int format, Function<String, String> versions) {
        return new SelectionContext(installed, versions, minecraft, format);
    }

    private static CommunityPack pack(String id, String plugin, String relation, int priority, String... also) {
        return new CommunityPack(id, plugin, relation, priority, List.of(also), "auto");
    }

    private static CommunityPack compat(
        String id,
        String plugin,
        String relation,
        String minecraft,
        String requires,
        String variant,
        boolean isDefault,
        int formatMin,
        int formatMax) {
        return new CommunityPack(
            id, plugin, relation, 0, List.of(), "auto", "1.0.0",
            minecraft, requires, variant, isDefault, formatMin, formatMax);
    }

    private static CommunityPack officialDefault(String id, String plugin, int priority) {
        return new CommunityPack(
            id, plugin, "official", priority, List.of(), "auto", "1.0.0",
            "", "", "", true, 0, 0);
    }

    private static CommunityPack addonDefault(String id, String variant, int priority) {
        return new CommunityPack(
            id, "Companion", "addon", priority, List.of(), "auto", "1.0.0",
            "", "", variant, true, 0, 0);
    }

    private static CommunityPack standalone(
        String id, String minecraft, String requires, int formatMin, int formatMax) {
        return new CommunityPack(
            id, "universal", "standalone", 0, List.of(), "auto", "1.0.0",
            minecraft, requires, "", false, formatMin, formatMax);
    }
}
