package com.sun.tools.javac.comp;

import static com.sun.tools.javac.code.Flags.SYNTHETIC;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.JCVoidType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

public class LambdaBackPorter {
  
  private static Map<Symbol, Map<JCLambda, JCClassDecl>> namingCache=new HashMap<>();
  
  static String nameForNextLambda(final JCClassDecl declaredIn) {
    return "Lambda$$" + (lambdasForClass(declaredIn).size() + 1);
      
  }
  
  static void cacheLambda(final JCClassDecl declaredIn, final JCLambda expr, final JCClassDecl implementation) {
    lambdasForClass(declaredIn).put(expr, implementation);
  }

  private static Map<JCLambda, JCClassDecl> lambdasForClass(
      final JCClassDecl declaredIn) {
    Map<JCLambda, JCClassDecl> lambdasForClass = namingCache.get(declaredIn.sym);
    if(lambdasForClass==null){
      lambdasForClass=new HashMap<>();
      namingCache.put(declaredIn.sym, lambdasForClass);
    }
    return lambdasForClass;
  }

  private TreeMaker make;
  private Attr attr;
  private Env<AttrContext> env;

  public LambdaBackPorter(TreeMaker make, Attr attr, Env<AttrContext> env) {
    super();
    this.make = make;
    this.attr = attr;
    this.env = env;
  }

  void initiateLambdaImplementation(final JCLambda that, Type currentTarget) {
    Name lambdaName = generateLambdaImplemenationName(that);
    final ClassSymbol implementationClassSymbol = attr.types
        .makeFunctionalInterfaceClass(this.env, lambdaName, that.targets,
            0);
    
    final MethodSymbol implementMethodSymbol = (MethodSymbol) implementationClassSymbol.members().elems.sym;
    //remove abstract flag from interface method
    if((implementMethodSymbol.flags() & Flags.ABSTRACT) != 0)
      implementMethodSymbol.flags_field-=Flags.ABSTRACT;

    final JCClassDecl lambdaClass = make.ClassDef(
        make.Modifiers(0), lambdaName,
        List.nil(), null, List.of(make.Type(currentTarget)), List.nil());
    
    cacheLambda(enclosingClass(), that, lambdaClass);

    lambdaClass.sym = implementationClassSymbol;
    lambdaClass.type = implementationClassSymbol.type;


    for (final VarSymbol param : implementMethodSymbol.params()) {
      param.adr = 0;
    }

    implementMethodSymbol.flags_field = Flags.PUBLIC;

    JCMethodDecl implementationMethod = make.MethodDef(implementMethodSymbol,
        returnStatementFrom(implementMethodSymbol));

    lambdaClass.defs = lambdaClass.defs.append(implementationMethod);

    enclosingClass().defs = enclosingClass().defs.append(lambdaClass);

    // this.attr.enter.typeEnvs.put(implementationClassSymbol,
    // this.attr.enter.classEnv(lambdaClass, this.env));

    // lambdaClass.accept(this.attr.enter);//ensure that the class' environment
    // is known, by simulating enter

  }

  private JCClassDecl enclosingClass() {
    return this.env.enclClass;
  }

  JCTree implementLambdaClass(JCLambda tree, MethodSymbol sym,
      JCMethodDecl lambdaDecl, ListBuffer<JCExpression> syntheticInits,
      List<JCExpression> indy_args, TransTypes trans) {

    // make lambda method default accessible instead of private, to avoid
    // separate access method
    removeFlag(lambdaDecl, Flags.PRIVATE);

    // instance method
    boolean isInstanceMethod = !sym.isStatic();
    final JCExpression lambdaMethodSymbol;

    // JCIdent targetType = make.Ident(tree.targets.head.tsym);
    // targetType.type = tree.targets.head;
    JCClassDecl lambdaImplClassDef = findLambdaImplSkeleton(tree);

    //short cut to bridge logic, since we are not in a state for the normal transtypes visitor
    final ListBuffer<JCTree> bridges = new ListBuffer<JCTree>();
    trans.addBridges(lambdaImplClassDef.pos(), lambdaImplClassDef.sym, bridges);
    lambdaImplClassDef.defs = lambdaImplClassDef.defs.appendList(bridges);

    ClassSymbol lambdaImplClassSymbol = lambdaImplClassDef.sym;

    List<JCTree> fieldDefinitions = List.nil();
    List<JCVariableDecl> constructorParameters = List.nil();
    List<JCStatement> constructorStatements = List.of(make
        .Exec(makeSuperConstructorCall()));
    // ClassSymbol classSymbol =
    // this.types.makeFunctionalInterfaceClass(this.attrEnv, lambdaClassName,
    // tree.targets, modifiers.flags);
    List<JCExpression> fieldParameters = List.nil();

    final MethodSymbol constructorSymbol = new MethodSymbol(Flags.GENERATEDCONSTR | Flags.ACYCLIC, attr.names.init,
        new MethodType(List.nil(), voidType(), List.nil(),
            lambdaImplClassSymbol.type.tsym), lambdaImplClassSymbol);
    final JCMethodDecl implementingMethod = (JCMethodDecl) lambdaImplClassDef.defs.head;
    constructorSymbol.params = List.nil();
    JCMethodDecl constructor = make.MethodDef(constructorSymbol,
        make.Block(0, List.nil()));

    int fieldIndex = 1;
    for (final JCExpression arg : indy_args) {
      JCExpression argType = make.Type(arg.type);
      Name fieldName = attr.names.fromString("field" + fieldIndex);

      JCVariableDecl fieldDef = make
          .VarDef(make.Modifiers(Flags.PRIVATE | Flags.FINAL), fieldName,
              argType, null);
      fieldDef.sym = new VarSymbol((long) (Flags.PRIVATE | Flags.FINAL)
          | SYNTHETIC, fieldName, arg.type, lambdaImplClassSymbol);
      lambdaImplClassSymbol.members().enter(fieldDef.sym);
      fieldDefinitions = fieldDefinitions.append(fieldDef);
      if (fieldIndex > 1 || !isInstanceMethod) {// add extra parameters apart
                                                // from first if lambda method
                                                // is instance method
        fieldParameters = fieldParameters.append(make.Ident(fieldDef.sym));
      }
      Name paramName = attr.names.fromString("param" + fieldIndex++);
      JCVariableDecl paramDef = make
          .Param(paramName, arg.type, constructor.sym);
      paramDef.sym.adr = 0;
      constructorParameters = constructorParameters.append(paramDef);
      constructorSymbol.params = constructorSymbol.params.append(paramDef.sym);
      ((MethodType) constructorSymbol.type).argtypes = ((MethodType) constructorSymbol.type).argtypes
          .append(arg.type);

      constructorStatements = constructorStatements.append(make.Assignment(
          fieldDef.sym, make.Ident(paramDef)));

    }

    constructor.params = constructorParameters;
    constructor.body.stats = constructorStatements;
    
    lambdaImplClassSymbol.members().enter(constructorSymbol);

    lambdaImplClassDef.defs = lambdaImplClassDef.defs.prependList(fieldDefinitions);
    lambdaImplClassDef.defs = lambdaImplClassDef.defs.prepend(constructor);

    if (isInstanceMethod) // call instance method on first arg which is "this"
      lambdaMethodSymbol = make.Select(
          make.Ident(((JCVariableDecl) fieldDefinitions.head).sym), sym);
    else
      lambdaMethodSymbol = make.QualIdent(sym);

    TypeSymbol lambdaSymbol = ((JCLambda) tree).type.tsym;
    MethodSymbol anonymousMethodSymbol = (MethodSymbol) attr.types
        .findDescriptorSymbol(lambdaSymbol).clone(lambdaImplClassSymbol);
    List<JCVariableDecl> lambdaCallParameters = List.nil();
    for (JCVariableDecl param : tree.params) {
      lambdaCallParameters = lambdaCallParameters.append(make.Param(param.name,
          param.type, anonymousMethodSymbol));
    }

    final JCMethodInvocation lambdaMethodCall = make.App(lambdaMethodSymbol,
        anonymousParameterValues(implementingMethod.params, fieldParameters));
    lambdaMethodCall.type = sym.type.getReturnType();

    if (isVoid(lambdaDecl.type))
      implementingMethod.body = make.Block((long) 0,
          List.of((JCStatement) make.Exec(lambdaMethodCall)));
    else
      implementingMethod.body = make.Block(0,
          List.of(make.Return(lambdaMethodCall)));

    lambdaImplClassDef.accept(trans);

    final JCNewClass newClass = make.NewClass(syntheticInits.first(), null,
        make.Ident(lambdaImplClassSymbol.name), indy_args, null);
    newClass.constructor = constructor.sym;
    newClass.type = lambdaImplClassSymbol.type;
    return newClass;
  }

  private JCMethodInvocation makeSuperConstructorCall() {
    JCIdent superMethod = make.Ident(attr.names._super);
    for (final Symbol method : attr.syms.objectType.tsym.getEnclosedElements()) {
      if (method.isConstructor()) {// only one constructor in Object
        superMethod.sym = method;
        superMethod.type = method.type;
        break;
      }
    }

    final JCMethodInvocation superCall = make.Apply(List.nil(), superMethod,
        List.nil());
    superCall.type = voidType();
    return superCall;
  }

  private JCClassDecl findLambdaImplSkeleton(final JCLambda tree) {
    return lambdasForClass(enclosingClass()).get(tree);

  }

  private void removeFlag(JCMethodDecl method, long flag) {
    long lambdaFlags = method.sym.flags_field;
    lambdaFlags = lambdaFlags - flag;
    method.sym.flags_field = lambdaFlags;
    method.mods = make.Modifiers(lambdaFlags);
  }

  private List<JCExpression> anonymousParameterValues(
      List<JCVariableDecl> params, List<JCExpression> fieldArgs) {
    List<JCExpression> parameterExpressions = fieldArgs;
    for (final JCVariableDecl param : params) {
      parameterExpressions = parameterExpressions.append(make.Ident(param.sym));
    }
    return parameterExpressions;
  }

  private JCBlock returnStatementFrom(MethodSymbol methodSymbol) {

    final MethodType type = (MethodType) methodSymbol.type;
    TypeTag tag = type.restype.getTag();
    if (isVoid(type))// void return type, return empty block
      return make.Block(0, List.nil());
    // numeric type, give 0 literal
    if (Arrays.asList(TypeTag.BYTE, TypeTag.CHAR, TypeTag.SHORT, TypeTag.INT,
        TypeTag.LONG, TypeTag.FLOAT, TypeTag.DOUBLE).contains(tag))
      return returnBlock(make.Literal(tag, 0), type.restype);
    if (TypeTag.BOOLEAN.equals(tag))
      return returnBlock(make.Literal(false), type.restype);

    // other cases return null literal
    return returnBlock(make.Literal(TypeTag.BOT, null), type.restype);

  }

  private boolean isVoid(final Type type) {
    return voidType().equals(type.getReturnType());
  }

  private JCVoidType voidType() {
    return attr.syms.voidType;
  }

  private JCBlock returnBlock(final JCLiteral literal, Type restype) {
    literal.type = restype;
    return make.Block(0, List.of(make.Return(literal)));
  }

  Name generateLambdaImplemenationName(JCLambda that) {
    return attr.names.fromString(nameForNextLambda(enclosingClass()));
  }

}
