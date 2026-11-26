package moe.nekomc.resourcepack.pack;

import java.util.List;

/** 服务端绑定选择的结果：纳入的包与每个被跳过包的原因。 */
public record CommunitySelection(List<CommunityPack> selected, List<CommunitySkip> skipped) {

    public CommunitySelection {
        selected = selected == null ? List.of() : List.copyOf(selected);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
}
