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

public class Link {
    public static final GlobType TYPE;

    public static final StringField fromField;

    public static final StringField toField;

    @InitUniqueKey
    public static final Key KEY;


    static {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Link");
        fromField = typeBuilder.declareStringField("from");
        toField = typeBuilder.declareStringField("to");
        typeBuilder.register(GlobCreateFromAnnotation.class, Link::create);
        TYPE = typeBuilder.build();
        KEY = KeyBuilder.newEmptyKey(TYPE);

//        GlobTypeLoader loader = GlobTypeLoaderFactory.create(Link.class);
//        loader.register(GlobCreateFromAnnotation.class, Link::create);
//        loader.load();
    }

    private static Glob create(Annotation annotation) {
        return TYPE.instantiate()
                .set(fromField, ((Link_) annotation).from())
                .set(toField, ((Link_) annotation).to());
    }
}
