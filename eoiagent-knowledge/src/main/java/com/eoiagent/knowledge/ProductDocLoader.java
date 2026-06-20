package com.eoiagent.knowledge;

/** Loads product help/manual documents ({@code PRODUCT_DOC}). */
public final class ProductDocLoader implements DocumentLoader {

    @Override
    public String sourceType() {
        return "PRODUCT_DOC";
    }
}
