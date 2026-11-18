package com.privacy.browser.images;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImageBindingTest {
    @Test public void rebindingTheSameRowInvalidatesItsOlderCallback() {
        ImageBinding slot = new ImageBinding();
        List<String> shown = new ArrayList<>();
        Consumer<String> older = slot.bind().whileCurrent(() -> true, shown::add);
        Consumer<String> newer = slot.bind().whileCurrent(() -> true, shown::add);
        newer.accept("new cover for row 4");
        older.accept("old cover for row 4");
        assertEquals(List.of("new cover for row 4"), shown);
    }

    @Test public void recycleAndDialogDismissPreventLatePaints() {
        ImageBinding slot = new ImageBinding();
        List<String> shown = new ArrayList<>();
        Consumer<String> delayed = slot.bind().whileCurrent(() -> true, shown::add);
        slot.clear();
        delayed.accept("recycled row");
        Consumer<String> dismissed = slot.bind().whileCurrent(() -> false, shown::add);
        dismissed.accept("closed dialog");
        slot.bind().<String>whileCurrent(() -> true, shown::add).accept(null);
        assertTrue(shown.isEmpty());
    }

    @Test public void lateIconCannotReplaceAnAlreadyShownCover() {
        ImageBinding.Bound binding = new ImageBinding().bind();
        AtomicBoolean hasCover = new AtomicBoolean();
        List<String> shown = new ArrayList<>();
        Consumer<String> icon = binding.whileCurrent(() -> !hasCover.get(), shown::add);
        Consumer<String> cover = binding.whileCurrent(() -> true, image -> {
            hasCover.set(true);
            shown.add(image);
        });
        cover.accept("cover");
        icon.accept("icon");
        assertEquals(List.of("cover"), shown);
    }
}
