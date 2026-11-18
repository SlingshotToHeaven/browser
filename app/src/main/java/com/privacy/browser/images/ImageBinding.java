package com.privacy.browser.images;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread ownership of a row or dialog image. A new bind invalidates even the same item id. */
public final class ImageBinding {
    private Bound current;

    public Bound bind() {
        return current = new Bound();
    }

    public void clear() {
        current = null;
    }

    public final class Bound {
        private Bound() {}

        public <V> Consumer<V> whileCurrent(BooleanSupplier visible, Consumer<V> display) {
            return image -> {
                if (image != null && current == this && visible.getAsBoolean()) display.accept(image);
            };
        }
    }
}
