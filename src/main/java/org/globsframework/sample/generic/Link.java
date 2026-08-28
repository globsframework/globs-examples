package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Key;
import org.globsframework.core.model.KeyBuilder;

public class Link {
    public static final GlobType TYPE;

    public static final StringField fromField;

    public static final StringField toField;

    public static final Key KEY;


    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Link");
        fromField = typeBuilder.declareStringField("from");
        toField = typeBuilder.declareStringField("to");
        TYPE = typeBuilder.build();
        KEY = KeyBuilder.newEmptyKey(TYPE);
    }
}
