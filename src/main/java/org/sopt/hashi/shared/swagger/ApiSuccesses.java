package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** {@link ApiSuccess} 반복 선언용 컨테이너. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiSuccesses {

    ApiSuccess[] value();
}
