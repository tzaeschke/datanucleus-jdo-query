/**********************************************************************
Copyright (c) 2010 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
   ...
**********************************************************************/
package org.datanucleus.jdo.query;

import java.io.IOException;
import java.io.Writer;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.jdo.annotations.NotPersistent;
import javax.jdo.annotations.PersistenceCapable;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;

import org.datanucleus.api.jdo.query.BooleanExpressionImpl;
import org.datanucleus.api.jdo.query.ByteExpressionImpl;
import org.datanucleus.api.jdo.query.CharacterExpressionImpl;
import org.datanucleus.api.jdo.query.CollectionExpressionImpl;
import org.datanucleus.api.jdo.query.DateExpressionImpl;
import org.datanucleus.api.jdo.query.DateTimeExpressionImpl;
import org.datanucleus.api.jdo.query.ExpressionType;
import org.datanucleus.api.jdo.query.JDOTypesafeQuery;
import org.datanucleus.api.jdo.query.ListExpressionImpl;
import org.datanucleus.api.jdo.query.MapExpressionImpl;
import org.datanucleus.api.jdo.query.NumericExpressionImpl;
import org.datanucleus.api.jdo.query.ObjectExpressionImpl;
import org.datanucleus.api.jdo.query.PersistableExpressionImpl;
import org.datanucleus.api.jdo.query.StringExpressionImpl;
import org.datanucleus.api.jdo.query.TimeExpressionImpl;
import org.datanucleus.query.typesafe.BooleanExpression;
import org.datanucleus.query.typesafe.ByteExpression;
import org.datanucleus.query.typesafe.CharacterExpression;
import org.datanucleus.query.typesafe.CollectionExpression;
import org.datanucleus.query.typesafe.DateExpression;
import org.datanucleus.query.typesafe.DateTimeExpression;
import org.datanucleus.query.typesafe.ListExpression;
import org.datanucleus.query.typesafe.MapExpression;
import org.datanucleus.query.typesafe.NumericExpression;
import org.datanucleus.query.typesafe.ObjectExpression;
import org.datanucleus.query.typesafe.PersistableExpression;
import org.datanucleus.query.typesafe.StringExpression;
import org.datanucleus.query.typesafe.TimeExpression;
import org.datanucleus.util.AnnotationProcessorUtils;
import org.datanucleus.util.AnnotationProcessorUtils.TypeCategory;

/**
 * Annotation processor for JDO to generate "dummy" classes for all persistable classes for use with the 
 * Typesafe Query API. Any class ({MyClass}) that has a JDO "class" annotation will have a stub class 
 * (Q{MyClass}) generated.
 * <ul>
 * <li>For each managed class X in package p, a metamodel class QX in package p is created.</li>
 * <li>The name of the metamodel class is derived from the name of the managed class by prepending "Q" 
 * to the name of the managed class.</li>
 * </ul>
 * <p>
 * This processor can generate classes in two modes.
 * <ul>
 * <li>Property access - so users type in "field1()", "field1().field2()" etc. 
 * Specify the compiler argument "queryMode" as "PROPERTY" to get this</li>
 * <li>Field access - so users type in "field1", "field1.field2". This is the default.</li>
 * </ul>
 * </p>
 * TODO Change "supportedAnnotationTypes" to be "*" and then detect XML files and generate the required class
 */
@SupportedAnnotationTypes({"javax.jdo.annotations.PersistenceCapable"})
public class JDOQueryProcessor extends AbstractProcessor
{
    // use "javac -AqueryMode=FIELD" to use fields
    public final static String OPTION_MODE = "queryMode";

    private final static int MODE_FIELD = 1;
    private final static int MODE_PROPERTY = 2;

    public int queryMode = MODE_FIELD;
    public int fieldDepth = 5;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv)
    {
        super.init(processingEnv);

        // Get the query mode
        String queryMode = processingEnv.getOptions().get(OPTION_MODE);
        if (queryMode != null && queryMode.equalsIgnoreCase("FIELD"))
        {
            this.queryMode = MODE_FIELD;
        }
    }

    /* (non-Javadoc)
     * @see javax.annotation.processing.AbstractProcessor#process(java.util.Set, javax.annotation.processing.RoundEnvironment)
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv)
    {
        if (roundEnv.processingOver())
        {
            return false;
        }

        Set<? extends Element> elements = roundEnv.getRootElements();
        for (Element e : elements)
        {
            if (e instanceof TypeElement)
            {
                processClass((TypeElement)e);
            }
        }
        return false;
    }

    /**
     * Handler for processing a JDO annotated class to create the criteria class stub.
     * @param el The class element
     */
    protected void processClass(TypeElement el)
    {
        if (el == null || !isJDOAnnotated(el))
        {
            return;
        }

        // TODO Support specification of the location for writing the class source files
        // TODO Set references to other classes to be the class name and put the package in the imports
        Elements elementUtils = processingEnv.getElementUtils();
        String className = elementUtils.getBinaryName(el).toString();
        String pkgName = className.substring(0, className.lastIndexOf('.'));
        String classSimpleName = className.substring(className.lastIndexOf('.') + 1);
        String classSimpleNameNew = JDOTypesafeQuery.getQueryClassNameForClassName(classSimpleName);
        String classNameNew = pkgName + "." + classSimpleNameNew;
        System.out.println("DataNucleus : JDO Query - " + className + " -> " + classNameNew);

        TypeElement superEl = getPersistentSupertype(el);
        try
        {
            JavaFileObject javaFile = processingEnv.getFiler().createSourceFile(classNameNew);
            Writer w = javaFile.openWriter();
            try
            {
                // Package declaration
                w.append("package " + pkgName + ";\n");
                w.append("\n");

                // Imports
                String typesafeCls = PersistableExpression.class.getName();
                String typesafePkg = typesafeCls.substring(0, typesafeCls.lastIndexOf('.'));
                w.append("import " + typesafePkg + ".*;\n");
                String typesafeImplCls = PersistableExpressionImpl.class.getName();
                String typesafeImplPkg = typesafeImplCls.substring(0, typesafeImplCls.lastIndexOf('.'));
                w.append("import " + typesafeImplPkg + ".*;\n");
                w.append("\n");

                // Class declaration
                w.append("public class " + classSimpleNameNew);
                if (superEl != null)
                {
                    // "public class QASub extends QA"
                    String superClassName = elementUtils.getBinaryName(superEl).toString();
                    w.append(" extends ").append(superClassName.substring(0, superClassName.lastIndexOf('.')+1));
                    w.append(JDOTypesafeQuery.getQueryClassNameForClassName(superClassName.substring(superClassName.lastIndexOf('.')+1)));
                }
                else
                {
                    // "public class QA extends PersistableExpressionImpl<A> implements PersistableExpression<A>"
                    w.append(" extends ").append(PersistableExpressionImpl.class.getName());
                    w.append("<" + classSimpleName + ">");
                    w.append(" implements ").append(PersistableExpression.class.getSimpleName() + "<" + classSimpleName + ">");
                }
                w.append("\n");
                w.append("{\n");

                String indent = "    ";

                // Add static accessor for the candidate of this type
                w.append(indent).append("public static final ").append(classSimpleNameNew).append(" jdoCandidate");
                w.append(" = candidate(\"this\");\n");
                w.append("\n");

                // Add static method to generate candidate of this type with a particular name
                w.append(indent).append("public static " + classSimpleNameNew + " candidate(String name)\n");
                w.append(indent).append("{\n");
                w.append(indent).append(indent).append("return new ").append(classSimpleNameNew);
                w.append("(null, name, " + fieldDepth + ");\n");
                w.append(indent).append("}\n");
                w.append("\n");

                // Add static method to generate candidate of this type for default name ("this")
                w.append(indent).append("public static " + classSimpleNameNew + " candidate()\n");
                w.append(indent).append("{\n");
                w.append(indent).append(indent).append("return jdoCandidate;\n");
                w.append(indent).append("}\n");
                w.append("\n");

                // Add static method to generate parameter of this type
                w.append(indent).append("public static " + classSimpleNameNew + " parameter(String name)\n");
                w.append(indent).append("{\n");
                w.append(indent).append(indent).append("return new ").append(classSimpleNameNew);
                w.append("(" + classSimpleName + ".class, name, ExpressionType.PARAMETER);\n");
                w.append(indent).append("}\n");
                w.append("\n");

                // Add static method to generate variable of this type
                w.append(indent).append("public static " + classSimpleNameNew + " variable(String name)\n");
                w.append(indent).append("{\n");
                w.append(indent).append(indent).append("return new ").append(classSimpleNameNew);
                w.append("(" + classSimpleName + ".class, name, ExpressionType.VARIABLE);\n");
                w.append(indent).append("}\n");
                w.append("\n");

                // Add fields for persistable members
                List<? extends Element> members = getPersistentMembers(el);
                if (members != null)
                {
                    Iterator<? extends Element> iter = members.iterator();
                    while (iter.hasNext())
                    {
                        Element member = iter.next();
                        if (member.getKind() == ElementKind.FIELD ||
                            (member.getKind() == ElementKind.METHOD && AnnotationProcessorUtils.isJavaBeanGetter((ExecutableElement) member)))
                        {
                            TypeMirror type = AnnotationProcessorUtils.getDeclaredType(member);
                            String memberName = AnnotationProcessorUtils.getMemberName(member);
                            String intfName = getExpressionInterfaceNameForType(type);

                            if (queryMode == MODE_FIELD)
                            {
                                w.append(indent).append("public final ").append(intfName);
                                w.append(" ").append(memberName).append(";\n");
                            }
                            else
                            {
                                w.append(indent).append("private ").append(intfName);
                                w.append(" ").append(memberName).append(";\n");
                            }
                        }
                    }
                }
                w.append("\n");

                // ========== Constructor(PersistableExpression parent, String name, int depth) ==========
                w.append(indent).append("public " + classSimpleNameNew).append("(");
                w.append(PersistableExpression.class.getSimpleName() + " parent, String name, int depth)\n");
                w.append(indent).append("{\n");
                if (superEl != null)
                {
                    w.append(indent).append("    super(parent, name, depth);\n");
                }
                else
                {
                    w.append(indent).append("    super(parent, name);\n");
                }
                if (queryMode == MODE_FIELD && members != null)
                {
                    // Initialise all fields
                    Iterator<? extends Element> iter = members.iterator();
                    while (iter.hasNext())
                    {
                        Element member = iter.next();
                        if (member.getKind() == ElementKind.FIELD ||
                            (member.getKind() == ElementKind.METHOD && AnnotationProcessorUtils.isJavaBeanGetter((ExecutableElement) member)))
                        {
                            TypeMirror type = AnnotationProcessorUtils.getDeclaredType(member);
                            String memberName = AnnotationProcessorUtils.getMemberName(member);
                            String implClassName = getExpressionImplClassNameForType(type);
                            if (isPersistableType(type))
                            {
                                // if (depth > 0)
                                // {
                                //     this.{field} = new {ImplType}(this, memberName, depth-1);
                                // }
                                // else
                                // {
                                //     this.{field} = null;
                                // }
                                w.append(indent).append(indent).append("if (depth > 0)\n");
                                w.append(indent).append(indent).append("{\n");
                                w.append(indent).append(indent).append(indent).append("this.").append(memberName);
                                w.append(" = new ").append(implClassName).append("(this, \"" + memberName + "\", depth-1);\n");
                                w.append(indent).append(indent).append("}\n");
                                w.append(indent).append(indent).append("else\n");
                                w.append(indent).append(indent).append("{\n");
                                w.append(indent).append(indent).append(indent).append("this.").append(memberName);
                                w.append(" = null;\n");
                                w.append(indent).append(indent).append("}\n");
                            }
                            else
                            {
                                // this.{field} = new {ImplType}(this, memberName);
                                w.append(indent).append(indent).append("this.").append(memberName);
                                w.append(" = new ").append(implClassName).append("(this, \"" + memberName + "\");\n");
                            }
                        }
                    }
                }
                w.append(indent).append("}\n");
                w.append("\n");

                // ========== Constructor(Class type, String name, ExpressionType exprType) ==========
                w.append(indent).append("public " + classSimpleNameNew).append("(");
                w.append(Class.class.getSimpleName() + " type, String name, " + ExpressionType.class.getName() + " exprType)\n");
                w.append(indent).append("{\n");
                w.append(indent).append("    super(type, name, exprType);\n");
                if (queryMode == MODE_FIELD && members != null)
                {
                    // Initialise all fields
                    Iterator<? extends Element> iter = members.iterator();
                    while (iter.hasNext())
                    {
                        Element member = iter.next();
                        if (member.getKind() == ElementKind.FIELD ||
                            (member.getKind() == ElementKind.METHOD && AnnotationProcessorUtils.isJavaBeanGetter((ExecutableElement) member)))
                        {
                            TypeMirror type = AnnotationProcessorUtils.getDeclaredType(member);
                            String memberName = AnnotationProcessorUtils.getMemberName(member);
                            String implClassName = getExpressionImplClassNameForType(type);
                            if (isPersistableType(type))
                            {
                                // this.{field} = new {ImplType}(this, memberName, fieldDepth);
                                w.append(indent).append(indent).append("this.").append(memberName);
                                w.append(" = new ").append(implClassName).append("(this, \"" + memberName + "\", " + fieldDepth + ");\n");
                            }
                            else
                            {
                                // this.{field} = new {ImplType}(this, memberName);
                                w.append(indent).append(indent).append("this.").append(memberName);
                                w.append(" = new ").append(implClassName).append("(this, \"" + memberName + "\");\n");
                            }
                        }
                    }
                }
                w.append(indent).append("}\n");

                // Property accessors
                if (queryMode == MODE_PROPERTY && members != null)
                {
                    Iterator<? extends Element> iter = members.iterator();
                    while (iter.hasNext())
                    {
                        Element member = iter.next();
                        if (member.getKind() == ElementKind.FIELD ||
                            (member.getKind() == ElementKind.METHOD && AnnotationProcessorUtils.isJavaBeanGetter((ExecutableElement) member)))
                        {
                            // public {type} {memberName}()
                            // {
                            //     if (memberVar == null)
                            //     {
                            //         this.memberVar = new {implClassName}(this, \"memberName\");
                            //     }
                            //     return this.memberVar;
                            // }
                            TypeMirror type = AnnotationProcessorUtils.getDeclaredType(member);
                            String memberName = AnnotationProcessorUtils.getMemberName(member);
                            String implClassName = getExpressionImplClassNameForType(type);
                            String intfName = getExpressionInterfaceNameForType(type);

                            w.append("\n");
                            w.append(indent).append("public ").append(intfName).append(" ");
                            w.append(memberName).append("()\n");
                            w.append(indent).append("{\n");
                            w.append(indent).append(indent).append("if (this.").append(memberName).append(" == null)\n");
                            w.append(indent).append(indent).append("{\n");
                            w.append(indent).append(indent).append(indent).append("this." + memberName);
                            w.append(" = new ").append(implClassName).append("(this, \"" + memberName + "\");\n");
                            w.append(indent).append(indent).append("}\n");
                            w.append(indent).append(indent).append("return this.").append(memberName).append(";\n");
                            w.append(indent).append("}\n");
                        }
                    }
                }

                w.append("}\n");
                w.flush();
            }
            finally
            {
                w.close();
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Convenience method to return the query expression interface name for a specified type.
     * @param type The type
     * @return The query expression interface name to use
     */
    private String getExpressionInterfaceNameForType(TypeMirror type)
    {
        if (type.getKind() == TypeKind.BOOLEAN)
        {
            return BooleanExpression.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.BYTE)
        {
            return ByteExpression.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.CHAR)
        {
            return CharacterExpression.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.DOUBLE)
        {
            return NumericExpression.class.getSimpleName() + "<Double>";
        }
        else if (type.getKind() == TypeKind.FLOAT)
        {
            return NumericExpression.class.getSimpleName() + "<Float>";
        }
        else if (type.getKind() == TypeKind.INT)
        {
            return NumericExpression.class.getSimpleName() + "<Integer>";
        }
        else if (type.getKind() == TypeKind.LONG)
        {
            return NumericExpression.class.getSimpleName() + "<Long>";
        }
        else if (type.getKind() == TypeKind.SHORT)
        {
            return NumericExpression.class.getSimpleName() + "<Short>";
        }
        else if (type.toString().equals(String.class.getName()))
        {
            return StringExpression.class.getSimpleName();
        }
        else if (type.toString().equals(Date.class.getName()))
        {
            return DateTimeExpression.class.getSimpleName();
        }
        else if (type.toString().equals(java.sql.Date.class.getName()))
        {
            return DateExpression.class.getSimpleName();
        }
        else if (type.toString().equals(java.sql.Time.class.getName()))
        {
            return TimeExpression.class.getSimpleName();
        }

        String typeName =
            AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, type, true);
        TypeCategory cat = AnnotationProcessorUtils.getTypeCategoryForTypeMirror(typeName);
        if (cat == TypeCategory.MAP)
        {
            return MapExpression.class.getSimpleName();
        }
        else if (cat == TypeCategory.LIST)
        {
            return ListExpression.class.getSimpleName();
        }
        else if (cat == TypeCategory.COLLECTION || cat == TypeCategory.SET)
        {
            return CollectionExpression.class.getSimpleName();
        }
        else
        {
            TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(type);
            if (typeElement != null && isJDOAnnotated(typeElement))
            {
                // Persistent field ("mydomain.Xxx" becomes "mydomain.QXxx")
                return typeName.substring(0, typeName.lastIndexOf('.')+1) + 
                    JDOTypesafeQuery.getQueryClassNameForClassName(typeName.substring(typeName.lastIndexOf('.')+1));
            }
            else
            {
                return ObjectExpression.class.getSimpleName() + "<" + type.toString() + ">";
            }
        }
    }

    /**
     * Convenience method to return the query expression implementation name for a specified type.
     * @param type The type
     * @return The query expression implementation class name to use
     */
    private String getExpressionImplClassNameForType(TypeMirror type)
    {
        if (type.getKind() == TypeKind.BOOLEAN)
        {
            return BooleanExpressionImpl.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.BYTE)
        {
            return ByteExpressionImpl.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.CHAR)
        {
            return CharacterExpressionImpl.class.getSimpleName();
        }
        else if (type.getKind() == TypeKind.DOUBLE)
        {
            return NumericExpressionImpl.class.getSimpleName() + "<Double>";
        }
        else if (type.getKind() == TypeKind.FLOAT)
        {
            return NumericExpressionImpl.class.getSimpleName() + "<Float>";
        }
        else if (type.getKind() == TypeKind.INT)
        {
            return NumericExpressionImpl.class.getSimpleName() + "<Integer>";
        }
        else if (type.getKind() == TypeKind.LONG)
        {
            return NumericExpressionImpl.class.getSimpleName() + "<Long>";
        }
        else if (type.getKind() == TypeKind.SHORT)
        {
            return NumericExpressionImpl.class.getSimpleName() + "<Short>";
        }
        else if (type.toString().equals(String.class.getName()))
        {
            return StringExpressionImpl.class.getSimpleName();
        }
        else if (type.toString().equals(Date.class.getName()))
        {
            return DateTimeExpressionImpl.class.getSimpleName();
        }
        else if (type.toString().equals(java.sql.Date.class.getName()))
        {
            return DateExpressionImpl.class.getSimpleName();
        }
        else if (type.toString().equals(java.sql.Time.class.getName()))
        {
            return TimeExpressionImpl.class.getSimpleName();
        }

        String typeName =
            AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, type, true);
        TypeCategory cat = AnnotationProcessorUtils.getTypeCategoryForTypeMirror(typeName);
        if (cat == TypeCategory.MAP)
        {
            return MapExpressionImpl.class.getSimpleName();
        }
        else if (cat == TypeCategory.LIST)
        {
            return ListExpressionImpl.class.getSimpleName();
        }
        else if (cat == TypeCategory.COLLECTION || cat == TypeCategory.SET)
        {
            return CollectionExpressionImpl.class.getSimpleName();
        }
        else
        {
            TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(type);
            if (typeElement != null && isJDOAnnotated(typeElement))
            {
                // Persistent field ("mydomain.Xxx" becomes "mydomain.QXxx")
                return typeName.substring(0, typeName.lastIndexOf('.')+1) + 
                    JDOTypesafeQuery.getQueryClassNameForClassName(typeName.substring(typeName.lastIndexOf('.')+1));
            }
            else
            {
                return ObjectExpressionImpl.class.getSimpleName() + "<" + type.toString() + ">";
            }
        }
    }

    /**
     * Convenience method to return the query expression implementation name for a specified type.
     * @param type The type
     * @return The query expression implementation class name to use
     */
    private boolean isPersistableType(TypeMirror type)
    {
        TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(type);
        if (typeElement != null && isJDOAnnotated(typeElement))
        {
            return true;
        }
        return false;
    }

    /**
     * Method to return the persistable members for the specified class.
     * @param el The class (TypeElement)
     * @return The members that are persistable (Element)
     */
    private List<? extends Element> getPersistentMembers(TypeElement el)
    {
        List<? extends Element> members = AnnotationProcessorUtils.getFieldMembers(el); // All fields needed
        if (members != null)
        {
            // Remove any non-persistent members
            Iterator<? extends Element> iter = members.iterator();
            while (iter.hasNext())
            {
                Element member = iter.next();
                boolean persistent = true;
                List<? extends AnnotationMirror> annots = member.getAnnotationMirrors();
                if (annots != null)
                {
                    Iterator<? extends AnnotationMirror> annotIter = annots.iterator();
                    while (annotIter.hasNext())
                    {
                        AnnotationMirror annot = annotIter.next();
                        if (annot.getAnnotationType().toString().equals(NotPersistent.class.getName()))
                        {
                            // Ignore this
                            persistent = false;
                            break;
                        }
                    }
                }
                if (!persistent)
                {
                    iter.remove();
                }
            }
        }
        return members;
    }

    /**
     * Method to find the next persistent supertype above this one.
     * @param element The element
     * @return Its next parent that is persistable (or null if no persistable predecessors)
     */
    public TypeElement getPersistentSupertype(TypeElement element)
    {
        TypeMirror superType = element.getSuperclass();
        if (superType == null || "java.lang.Object".equals(element.toString()))
        {
            return null;
        }

        TypeElement superElement = (TypeElement) processingEnv.getTypeUtils().asElement(superType);
        if (superElement == null || isJDOAnnotated(superElement))
        {
            return superElement;
        }
        return getPersistentSupertype(superElement);
    }

    /**
     * Convenience method to return if this class element has any of the defining JDO annotations.
     * @param el The class element
     * @return Whether it is to be considered a JDO annotated class
     */
    public static boolean isJDOAnnotated(TypeElement el)
    {
        if ((el.getAnnotation(PersistenceCapable.class) != null))
        {
            return true;
        }
        return false;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() 
    {
        return SourceVersion.latest();
    }
}