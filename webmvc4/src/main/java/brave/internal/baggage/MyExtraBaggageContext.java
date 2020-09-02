/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.internal.baggage;

import brave.baggage.MyBaggageField;
import brave.internal.Nullable;
import brave.propagation.MyTraceContext;
import brave.propagation.MyTraceContextOrSamplingFlags;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Most commonly, field storage is inside {@link MyTraceContext#extra()}.
 *
 * @see MyBaggageFields
 */
public final class MyExtraBaggageContext extends MyBaggageContext {
    static final MyExtraBaggageContext INSTANCE = new MyExtraBaggageContext();

    public static MyBaggageContext get() {
        return INSTANCE;
    }


    public static List<MyBaggageField> getAllFields(MyTraceContextOrSamplingFlags extracted) {
        if (extracted.context() != null) return getAllFields(extracted.context());
        return getAllFields(extracted.extra());
    }

    public static List<MyBaggageField> getAllFields(MyTraceContext context) {
        return getAllFields(context.extra());
    }

    public static Map<String, String> getAllValues(MyTraceContextOrSamplingFlags extracted) {
        if (extracted.context() != null) return getAllValues(extracted.context());
        return getAllValues(extracted.extra());
    }

    public static Map<String, String> getAllValues(MyTraceContext context) {
        return getAllValues(context.extra());
    }

    @Nullable
    public static MyBaggageField getFieldByName(MyTraceContextOrSamplingFlags extracted, String name) {
        if (extracted.context() != null) return getFieldByName(extracted.context(), name);
        return getFieldByName(getAllFields(extracted.extra()), name);
    }

    @Nullable public static MyBaggageField getFieldByName(MyTraceContext context, String name) {
        return getFieldByName(getAllFields(context.extra()), name);
    }

    @Override public String getValue(MyBaggageField field, MyTraceContextOrSamplingFlags extracted) {
        if (extracted.context() != null) return getValue(field, extracted.context());
        return getValue(field, extracted.extra());
    }

    @Override public String getValue(MyBaggageField field, MyTraceContext context) {
        return getValue(field, context.extra());
    }

    @Override public boolean updateValue(MyBaggageField field, MyTraceContextOrSamplingFlags extracted,
                                         @Nullable String value) {
        if (extracted.context() != null) return updateValue(field, extracted.context(), value);
        return updateValue(field, extracted.extra(), value);
    }

    @Override public boolean updateValue(MyBaggageField field, MyTraceContext context, String value) {
        return updateValue(field, context.extra(), value);
    }

    static List<MyBaggageField> getAllFields(List<Object> extraList) {
        MyBaggageFields extra = findExtra(MyBaggageFields.class, extraList);
        if (extra == null) return Collections.emptyList();
        return extra.getAllFields();
    }

    static Map<String, String> getAllValues(List<Object> extraList) {
        MyBaggageFields extra = findExtra(MyBaggageFields.class, extraList);
        if (extra == null) return Collections.emptyMap();
        return extra.getAllValues();
    }

    @Nullable static MyBaggageField getFieldByName(List<MyBaggageField> fields, String name) {
        if (name == null) throw new NullPointerException("name == null");
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("name is empty");
        for (MyBaggageField field : fields) {
            if (name.equals(field.name())) {
                return field;
            }
        }
        return null;
    }

    @Nullable static String getValue(MyBaggageField field, List<Object> extraList) {
        MyBaggageFields extra = findExtra(MyBaggageFields.class, extraList);
        if (extra == null) return null;
        return extra.getValue(field);
    }

    static boolean updateValue(MyBaggageField field, List<Object> extraList, @Nullable String value) {
        MyBaggageFields extra = findExtra(MyBaggageFields.class, extraList);
        return extra != null && extra.updateValue(field, value);
    }

    public static <T> T findExtra(Class<T> type, List<Object> extra) {
        if (type == null) throw new NullPointerException("type == null");
        for (int i = 0, length = extra.size(); i < length; i++) {
            Object nextExtra = extra.get(i);
            if (nextExtra.getClass() == type) return (T) nextExtra;
        }
        return null;
    }
}
