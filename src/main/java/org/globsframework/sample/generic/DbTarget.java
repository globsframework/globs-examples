package org.globsframework.sample.generic;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.GlobCreateFromAnnotation;
import org.globsframework.core.metamodel.annotations.InitUniqueKey;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;
import org.globsframework.core.model.KeyBuilder;

import java.lang.annotation.Annotation;

public class DbTarget {
    public static final GlobType TYPE;

    public static final StringField dbResource;

    @InitUniqueKey
    public static final Key KEY;

    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("DbTarget");
        dbResource = typeBuilder.declareStringField("dbResource");
        typeBuilder.register(GlobCreateFromAnnotation.class, DbTarget::create);
        TYPE = typeBuilder.build();
        KEY = KeyBuilder.newEmptyKey(TYPE);
    }

    private static Glob create(Annotation annotation) {
        try {
            return TYPE.instantiate()
                    .set(dbResource, ((GlobType) ((DbTarget_) annotation).value().getField("TYPE").get(null)).getName());
        } catch (Exception e) {
            throw new RuntimeException("Fail to extract TYPE");
        }
    }
}
