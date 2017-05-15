/**
 * Copyright (C) 2016-2017 DSpot Sp. z o.o
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dspot.declex.external;

import static com.helger.jcodemodel.JExpr.FALSE;
import static com.helger.jcodemodel.JExpr._null;
import static com.helger.jcodemodel.JExpr.invoke;
import static com.helger.jcodemodel.JExpr.lit;
import static com.helger.jcodemodel.JExpr.ref;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;

import org.androidannotations.AndroidAnnotationsEnvironment;
import org.androidannotations.ElementValidation;
import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EBean;
import org.androidannotations.handler.BaseAnnotationHandler;
import org.androidannotations.helper.ModelConstants;
import org.androidannotations.holder.EComponentHolder;

import com.dspot.declex.api.external.External;
import com.dspot.declex.api.external.ExternalPopulate;
import com.dspot.declex.api.external.ExternalRecollect;
import com.dspot.declex.api.external.NonExternal;
import com.dspot.declex.api.viewsinjection.Populate;
import com.dspot.declex.api.viewsinjection.Recollect;
import com.dspot.declex.helper.FilesCacheHelper.FileDependency;
import com.dspot.declex.helper.FilesCacheHelper.FileDetails;
import com.dspot.declex.override.util.DeclexAPTCodeModelHelper;
import com.dspot.declex.util.TypeUtils;
import com.dspot.declex.util.element.VirtualElement;
import com.helger.jcodemodel.JInvocation;
import com.helger.jcodemodel.JMethod;
import com.helger.jcodemodel.JVar;

public class ExternalHandler extends BaseAnnotationHandler<EComponentHolder> {
	
	public ExternalHandler(AndroidAnnotationsEnvironment environment) {
		this(External.class, environment);
		
		codeModelHelper = new DeclexAPTCodeModelHelper(environment);
	}

	public ExternalHandler(Class<? extends Annotation> targetClass, AndroidAnnotationsEnvironment environment) {
		super(targetClass, environment);
	}
	
	@Override
	public void getDependencies(Element element, Map<Element, Class<? extends Annotation>> dependencies) {
		
		//External in the super class will inject through ADI all the external methods
		if (element.getKind().equals(ElementKind.CLASS)) {
			
			dependencies.put(element, EBean.class);
			
			List<? extends Element> elems = element.getEnclosedElements();
			for (Element elem : elems) {
		
				if (elem.getModifiers().contains(Modifier.STATIC)) continue;
				if (elem.getModifiers().contains(Modifier.ABSTRACT)) continue;
				if (elem.getAnnotation(NonExternal.class) != null) continue;
								
				if (elem instanceof ExecutableElement) {
					if (!elem.getModifiers().contains(Modifier.PUBLIC)) continue;

					if (elem.getAnnotation(AfterInject.class) != null) continue;
					if (elem.getAnnotation(AfterViews.class) != null) continue;
					if (elem.getAnnotation(ExternalPopulate.class) != null) continue;
					if (elem.getAnnotation(ExternalRecollect.class) != null) continue;
					
					if (elem.getAnnotation(Populate.class) != null) {
						dependencies.put(elem, ExternalPopulate.class);
						continue;
					}
					
					if (elem.getAnnotation(Recollect.class) != null) {
						dependencies.put(elem, ExternalRecollect.class);
						continue;
					}
					
					List<? extends AnnotationMirror> annotations = elem.getAnnotationMirrors();
					for (AnnotationMirror annotation : annotations) {
						if (getEnvironment().getSupportedAnnotationTypes()
								            .contains(annotation.getAnnotationType().toString()))
						{
							dependencies.put(elem, External.class);
							break;
						}
					}
				} else {
					if (elem.getAnnotation(Populate.class) != null) {
						dependencies.put(elem, ExternalPopulate.class);
					}				
					if (elem.getAnnotation(Recollect.class) != null) {
						dependencies.put(elem, ExternalRecollect.class);
					}
				}
			}
		}
	}
	
	@Override
	public void validate(final Element element, final ElementValidation valid) {
		
		if (element.getAnnotation(AfterInject.class) != null) {
			valid.addError("You cannot use @External in an @AfterInject method");
			return;
		}
		
		if (element.getModifiers().contains(Modifier.STATIC)) {
			valid.addError("You cannot use @External in a static element");
			return;
		}
		
		if ((element instanceof ExecutableElement) && !element.getModifiers().contains(Modifier.PUBLIC)) {
			valid.addError("You can use @External only on public methods");
			return;
		}

		//TODO
		//Now the rootElement generated class depends on this element
		final Element rootElement = TypeUtils.getRootElement(element);
		final String generatedRootElementClass = TypeUtils.getGeneratedClassName(rootElement, getEnvironment());
		
		System.out.println("XX: " + generatedRootElementClass);
		if (filesCacheHelper.hasCachedFile(generatedRootElementClass)) {
			
			FileDetails details = filesCacheHelper.getFileDetails(generatedRootElementClass);
			System.out.println("XY: " + details);
			
			FileDependency dependency = filesCacheHelper.getFileDependency(((VirtualElement)element).getElement().getEnclosingElement().asType().toString());
			
			if (!details.dependencies.contains(dependency)) {		
				System.out.println("XZ: " + dependency);
				
				details.invalidate();
				valid.addError("Please rebuild the project to update the cache");
			}			
		}
		
		filesCacheHelper.addGeneratedClass(
				generatedRootElementClass, 
				((VirtualElement)element).getElement().getEnclosingElement()
			);

	}
	
	@Override
	public void process(Element element, EComponentHolder holder) {
		
		if (element instanceof VirtualElement) {
			
			String referenceName = ((VirtualElement) element).getReference().getSimpleName().toString();
			
			if (element instanceof ExecutableElement) {
				JMethod method = codeModelHelper.overrideAnnotatedMethod((ExecutableElement) element, holder, false, false);
				JInvocation invocation = invoke(ref(referenceName), method);
						
				if (method.type().fullName().toString().equals("void")) {
					method.body()._if(ref(referenceName).neNull())._then().add(invocation);
				} else {
					method.body()._if(ref(referenceName).neNull())._then()._return(invocation);
					
					if (method.type().fullName().equals("boolean")) method.body()._return(FALSE);
					else if (method.type().fullName().contains(".") 
							|| method.type().fullName().endsWith(ModelConstants.generationSuffix())) 
					        {method.body()._return(_null());} 
					else method.body()._return(lit(0));
				}
						
						                              ;
				for (JVar param : method.params()) {
					invocation.arg(ref(param.name()));
				}
				
			}
		}
		
		
	}

}
