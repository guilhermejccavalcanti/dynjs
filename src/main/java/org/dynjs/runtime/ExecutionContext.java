package org.dynjs.runtime;
import org.dynjs.Clock;
import org.dynjs.Config;
import org.dynjs.compiler.JSCompiler;
import org.dynjs.exception.ThrowException;
import org.dynjs.ir.IRJSFunction;
import org.dynjs.parser.ast.FunctionDeclaration;
import org.dynjs.parser.ast.VariableDeclaration;
import org.dynjs.runtime.BlockManager.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ExecutionContext {
  public static ExecutionContext createGlobalExecutionContext(DynJS runtime) {
    LexicalEnvironment env = LexicalEnvironment.newGlobalEnvironment(runtime);
    return new ExecutionContext(runtime, null, env, env, runtime.getGlobalObject(), false);
  }

  public static ExecutionContext createGlobalExecutionContext(DynJS runtime, InitializationListener listener) {
    ExecutionContext context = ExecutionContext.createEvalExecutionContext(runtime);
    listener.initialize(context);
    return context;
  }

  public static ExecutionContext createEvalExecutionContext(DynJS runtime) {
    return createGlobalExecutionContext(runtime);
  }

  private DynJS runtime;

  private ExecutionContext parent;

  private LexicalEnvironment lexicalEnvironment;

  private LexicalEnvironment variableEnvironment;

  private Object thisBinding;

  private boolean strict;

  private int lineNumber;

  private String fileName;

  private String debugContext = "<eval>";

  private VariableValues vars;

  private Object[] functionParameters;

  private Object functionReference;

  private List<StackElement> throwStack;

  public ExecutionContext(DynJS runtime, ExecutionContext parent, LexicalEnvironment lexicalEnvironment, LexicalEnvironment variableEnvironment, Object thisBinding, boolean strict) {
    this.runtime = runtime;
    this.parent = parent;
    this.lexicalEnvironment = lexicalEnvironment;
    this.variableEnvironment = variableEnvironment;
    this.thisBinding = thisBinding;
    this.strict = strict;
  }

  public void setFunctionParameters(Object[] args) {
    this.functionParameters = args;
  }

  public Object[] getFunctionParameters() {
    return functionParameters;
  }

  public VariableValues getVars() {
    return vars;
  }

  public VariableValues allocVars(int size, VariableValues parent) {
    vars = new VariableValues(size, parent);
    return vars;
  }

  public Object getFunctionReference() {
    return this.functionReference;
  }

  public ExecutionContext getParent() {
    return this.parent;
  }

  public LexicalEnvironment getLexicalEnvironment() {
    return this.lexicalEnvironment;
  }

  public LexicalEnvironment getVariableEnvironment() {
    return this.variableEnvironment;
  }

  public Object getThisBinding() {
    return this.thisBinding;
  }

  public boolean isStrict() {
    return this.strict;
  }

  public Clock getClock() {
    return this.runtime.getConfig().getClock();
  }

  public TimeZone getTimeZone() {
    return this.runtime.getConfig().getTimeZone();
  }

  public Locale getLocale() {
    return this.runtime.getConfig().getLocale();
  }

  void setStrict(boolean strict) {
    this.strict = strict;
  }

  public Reference resolve(String name) {
    Reference result = this.lexicalEnvironment.getIdentifierReference(this, name, isStrict());
    return result;
  }

  public void setLineNumber(int lineNumber) {
    this.lineNumber = lineNumber;
  }

  public int getLineNumber() {
    return this.lineNumber;
  }

  public String getFileName() {
    return this.fileName;
  }

  public Completion execute(JSProgram program) {
    try {
      ThreadContextManager.pushContext(this);
      setStrict(program.isStrict());
      this.fileName = program.getFileName();
      performDeclarationBindingInstantiation(program);
      try {
        return program.execute(this);
      } catch (ThrowException e) {
        throw e;
      }
    }  finally {
      ThreadContextManager.popContext();
    }
  }

  public Object eval(JSProgram eval, boolean direct) {
    try {
      ExecutionContext evalContext = createEvalExecutionContext(eval, direct);
      ThreadContextManager.pushContext(evalContext);
      Completion result = eval.execute(evalContext);
      return result.value;
    }  finally {
      ThreadContextManager.popContext();
    }
  }

  public Object call(JSFunction function, Object self, Object... args) {
    return call(null, function, self, args);
  }

  public Object call(Object functionReference, JSFunction function, Object self, Object... args) {
    ExecutionContext fnContext = null;
    try {
      fnContext = createFunctionExecutionContext(functionReference, function, self, args);
      ThreadContextManager.pushContext(fnContext);
      try {
        Object value = function.call(fnContext);
        if (value == null) {
          return Types.NULL;
        }
        return value;
      } catch (ThrowException e) {
        throw e;
      } catch (Throwable e) {
        throw new ThrowException(fnContext, e);
      }
    } catch (ThrowException t) {
      if (t.getCause() != null) {
        recordThrow(t.getCause(), fnContext);
      } else {
        if (t.getValue() instanceof Throwable) {
          recordThrow((Throwable) t.getValue(), fnContext);
        }
      }
      throw t;
    } catch (Throwable t) {
      recordThrow(t, fnContext);
      throw t;
    } finally {
      ThreadContextManager.popContext();
    }
  }

  public Object construct(Reference reference, Object... args) {
    Object value = reference.getValue(this);
    if (value instanceof JSFunction) {
      return construct(reference, (JSFunction) value, args);
    }
    return null;
  }

  public Object construct(Object reference, JSFunction function, Object... args) {
    if (!function.isConstructor()) {
      throw new ThrowException(this, createTypeError("not a constructor"));
    }
    Object ctorName = function.get(this, "name");
    if (ctorName == Types.UNDEFINED) {
      if (reference instanceof Reference) {
        ctorName = ((Reference) reference).getReferencedName();
      } else {
        ctorName = function.getDebugContext();
      }
    }
    JSObject obj = function.createNewObject(this);
    Object p = function.get(this, "prototype");
    if (p != Types.UNDEFINED && p instanceof JSObject) {
      obj.setPrototype((JSObject) p);
    } else {
      JSObject defaultObjectProto = getPrototypeFor("Object");
      obj.setPrototype(defaultObjectProto);
    }
    Object result = call(reference, function, obj, args);
    if (result instanceof JSObject) {
      obj = (JSObject) result;
    }
    ((JSObject) obj).defineNonEnumerableProperty(this.getGlobalObject(), "__ctor__", ctorName.toString());
    return obj;
  }

  protected void recordThrow(Throwable t, ExecutionContext fnContext) {
    if (this.throwStack == null) {
      this.throwStack = new ArrayList<StackElement>();
      this.throwStack.add(fnContext.getStackElement());
    } else {
    }
  }

  public boolean isThrowInProgress() {
    return this.throwStack != null && !this.throwStack.isEmpty();
  }

  public List<StackElement> getThrowStack() {
    return this.throwStack;
  }

  public void addThrowStack(List<StackElement> throwStack) {
    if (this.throwStack == null) {
      this.throwStack = new ArrayList<>();
    }
    this.throwStack.addAll(throwStack);
  }

  public ExecutionContext createEvalExecutionContext(JSProgram eval, boolean direct) {
    ExecutionContext context = null;
    Object evalThisBinding = null;
    LexicalEnvironment evalLexEnv = null;
    LexicalEnvironment evalVarEnv = null;
    if (!direct) {
      evalThisBinding = getGlobalObject();
      evalLexEnv = LexicalEnvironment.newGlobalEnvironment(getGlobalObject());
      evalVarEnv = LexicalEnvironment.newGlobalEnvironment(getGlobalObject());
    } else {
      evalThisBinding = this.thisBinding;
      evalLexEnv = this.getLexicalEnvironment();
      evalVarEnv = this.getVariableEnvironment();
    }
    if (eval.isStrict()) {
      LexicalEnvironment strictVarEnv = LexicalEnvironment.newDeclarativeEnvironment(this.getLexicalEnvironment());
      evalLexEnv = strictVarEnv;
      evalVarEnv = strictVarEnv;
    }
    context = new ExecutionContext(this.runtime, this, evalLexEnv, evalVarEnv, evalThisBinding, eval.isStrict());
    context.performFunctionDeclarationBindings(eval, true);
    context.performVariableDeclarationBindings(eval, true);
    context.fileName = eval.getFileName();
    return context;
  }

  public ExecutionContext createFunctionExecutionContext(Object functionReference, JSFunction function, Object thisArg, Object... arguments) {
    Object thisBinding = null;
    if (function.isStrict()) {
      thisBinding = thisArg;
    } else {
      if (thisArg == null || thisArg == Types.NULL || thisArg == Types.UNDEFINED) {
        thisBinding = getGlobalObject();
      } else {
        if (!(thisArg instanceof JSObject)) {
          thisBinding = Types.toThisObject(this, thisArg);
        } else {
          thisBinding = thisArg;
        }
      }
    }
    LexicalEnvironment scope = function.getScope();
    LexicalEnvironment localEnv = LexicalEnvironment.newDeclarativeEnvironment(scope);
    ExecutionContext context = new ExecutionContext(this.runtime, this, localEnv, localEnv, thisBinding, function.isStrict());
    if (!(function instanceof IRJSFunction)) {
      context.performDeclarationBindingInstantiation(function, arguments);
    }
    context.fileName = function.getFileName();
    context.debugContext = function.getDebugContext();
    context.functionReference = functionReference;
    context.setFunctionParameters(arguments);
    return context;
  }

  public Completion executeCatch(BasicBlock block, String identifier, Object thrown) {
    if (thrown instanceof Throwable && this.throwStack != null && !this.throwStack.isEmpty()) {
      StackTraceElement[] originalStack = ((Throwable) thrown).getStackTrace();
      List<StackTraceElement> newStack = new ArrayList<>();
      for (int i = 0; i < originalStack.length; ++i) {
        String cn = originalStack[i].getClassName();
        if (cn.startsWith("org.dynjs") || cn.startsWith("java.lang.invoke")) {
          break;
        }
        newStack.add(originalStack[i]);
      }
      int throwLen = this.throwStack.size();
      for (int i = throwLen - 1; i >= 0; --i) {
        newStack.add(throwStack.get(i).toStackTraceElement());
      }
      ((Throwable) thrown).setStackTrace(newStack.toArray(new StackTraceElement[0]));
    }
    LexicalEnvironment oldEnv = this.lexicalEnvironment;
    LexicalEnvironment catchEnv = LexicalEnvironment.newDeclarativeEnvironment(oldEnv);
    catchEnv.getRecord().createMutableBinding(this, identifier, false);
    catchEnv.getRecord().setMutableBinding(this, identifier, thrown, false);
    try {
      this.lexicalEnvironment = catchEnv;
      return block.call(this);
    } catch (ThrowException e) {
      throw e;
    } catch (Throwable t) {
      throw new ThrowException(this, t);
    } finally {
      this.lexicalEnvironment = oldEnv;
    }
  }

  public Completion executeWith(JSObject withObj, BasicBlock block) {
    LexicalEnvironment oldEnv = this.lexicalEnvironment;
    LexicalEnvironment withEnv = LexicalEnvironment.newObjectEnvironment(withObj, true, oldEnv);
    try {
      this.lexicalEnvironment = withEnv;
      return block.call(this);
    }  finally {
      this.lexicalEnvironment = oldEnv;
    }
  }

  private void performDeclarationBindingInstantiation(JSProgram program) {
    performFunctionDeclarationBindings(program, false);
    performVariableDeclarationBindings(program, false);
  }

  private void performDeclarationBindingInstantiation(JSFunction function, Object[] arguments) {
    String[] names = function.getFormalParameters();
    Object v = null;
    DeclarativeEnvironmentRecord env = (DeclarativeEnvironmentRecord) this.variableEnvironment.getRecord();
    for (int i = 0; i < names.length; ++i) {
      if ((i + 1) > arguments.length) {
        v = Types.UNDEFINED;
      } else {
        v = arguments[i];
      }
      env.assignMutableBinding(this, names[i], v, false, function.isStrict());
    }
    performFunctionDeclarationBindings(function, false);
    if (!env.hasBinding(this, "arguments")) {
      Arguments argsObj = createArgumentsObject(function, arguments);
      if (function.isStrict()) {
        env.createImmutableBinding("arguments");
        env.initializeImmutableBinding("arguments", argsObj);
      } else {
        env.createMutableBinding(this, "arguments", false);
        env.setMutableBinding(this, "arguments", argsObj, false);
      }
    }
    performVariableDeclarationBindings(function, false);
  }

  private Arguments createArgumentsObject(final JSFunction function, final Object[] arguments) {
    Arguments obj = new Arguments(getGlobalObject());
    obj.defineOwnProperty(this, "length", PropertyDescriptor.newDataPropertyDescriptor(arguments.length, true, true, false), false);
    String[] names = function.getFormalParameters();
    JSObject map = new DynObject(getGlobalObject());
    List<String> mappedNames = new ArrayList<>();
    final LexicalEnvironment env = getVariableEnvironment();
    for (int i = 0; i < arguments.length; ++i) {
      final Object val = arguments[i];
      obj.defineOwnProperty(this, "" + i, PropertyDescriptor.newDataPropertyDescriptor(val, true, true, true), false);
      if (i < names.length) {
        if (!function.isStrict()) {
          final String name = names[i];
          if (i < names.length) {
            if (!mappedNames.contains(name)) {
              mappedNames.add(name);
              PropertyDescriptor desc = new PropertyDescriptor();
              desc.setSetter(new ArgSetter(getGlobalObject(), env, name));
              desc.setGetter(new ArgGetter(getGlobalObject(), env, name));
              desc.setConfigurable(true);
              map.defineOwnProperty(this, "" + i, desc, false);
            }
          }
        }
      }
    }
    if (!mappedNames.isEmpty()) {
      obj.setParameterMap(map);
    }
    if (function.isStrict()) {
      final JSFunction thrower = (JSFunction) getGlobalObject().get(this, "__throwTypeError");
      obj.defineOwnProperty(this, "caller", PropertyDescriptor.newAccessorPropertyDescriptor(thrower, thrower), false);
      obj.defineOwnProperty(this, "callee", PropertyDescriptor.newAccessorPropertyDescriptor(thrower, thrower), false);
    } else {
      obj.defineOwnProperty(this, "callee", PropertyDescriptor.newDataPropertyDescriptor(function, true, true, false), false);
    }
    return obj;
  }

  private void performFunctionDeclarationBindings(final JSCode code, final boolean configurableBindings) {
    List<FunctionDeclaration> decls = code.getFunctionDeclarations();
    EnvironmentRecord env = this.variableEnvironment.getRecord();
    for (FunctionDeclaration each : decls) {
      String identifier = each.getIdentifier();
      if (!env.hasBinding(this, identifier)) {
        env.createMutableBinding(this, identifier, configurableBindings);
      } else {
        if (env.isGlobal()) {
          JSObject globalObject = ((ObjectEnvironmentRecord) env).getBindingObject();
          PropertyDescriptor existingProp = (PropertyDescriptor) globalObject.getProperty(this, identifier, false);
          if (existingProp.isConfigurable()) {
            globalObject.defineOwnProperty(this, identifier, PropertyDescriptor.newDataPropertyDescriptor(Types.UNDEFINED, true, configurableBindings, true), true);
          } else {
            if (existingProp.isAccessorDescriptor() || (!existingProp.isWritable() && !existingProp.isEnumerable())) {
              throw new ThrowException(this, createTypeError("unable to bind function \'" + identifier + "\'"));
            }
          }
        }
      }
      JSFunction function = getCompiler().compileFunction(this, identifier, each.getFormalParameters(), each.getBlock(), each.isStrict());
      function.setDebugContext(identifier);
      env.setMutableBinding(this, identifier, function, code.isStrict());
    }
  }

  private void performVariableDeclarationBindings(final JSCode code, final boolean configurableBindings) {
    List<VariableDeclaration> decls = code.getVariableDeclarations();
    EnvironmentRecord env = this.variableEnvironment.getRecord();
    for (VariableDeclaration decl : decls) {
      String identifier = decl.getIdentifier();
      if (!env.hasBinding(this, identifier)) {
        env.createMutableBinding(this, identifier, configurableBindings);
        env.setMutableBinding(this, identifier, Types.UNDEFINED, code.isStrict());
      }
    }
  }

  public Config getConfig() {
    return this.runtime.getConfig();
  }

  public GlobalObject getGlobalObject() {
    return this.runtime.getGlobalObject();
  }

  public JSCompiler getCompiler() {
    return this.runtime.getCompiler();
  }

  public BlockManager getBlockManager() {
    return getGlobalObject().getBlockManager();
  }

  public DynJS getRuntime() {
    return this.runtime;
  }

  public Reference createPropertyReference(Object base, String propertyName) {
    return new Reference(propertyName, base, isStrict());
  }

  public Entry retrieveBlockEntry(int statementNumber) {
    return getGlobalObject().retrieveBlockEntry(statementNumber);
  }

  public JSObject createTypeError(String message) {
    return createError("TypeError", message);
  }

  public JSObject createReferenceError(String message) {
    return createError("ReferenceError", message);
  }

  public JSObject createRangeError(String message) {
    return createError("RangeError", message);
  }

  public JSObject createSyntaxError(String message) {
    return createError("SyntaxError", message);
  }

  public JSObject createUriError(String message) {
    return createError("URIError", message);
  }

  public JSObject createError(String type, String message) {
    JSFunction func = (JSFunction) getGlobalObject().get(this, type);
    JSObject err = null;
    if (message == null) {
      err = (JSObject) construct((Object) null, func);
    } else {
      err = (JSObject) construct((Object) null, func, message);
    }
    return err;
  }

  public void collectStackElements(List<StackElement> elements) {
    elements.add(getStackElement());
    if (parent != null) {
      parent.collectStackElements(elements);
    }
  }

  protected StackElement getStackElement() {
    return new StackElement(this.fileName, this.lineNumber, this.debugContext);
  }

  public JSObject getPrototypeFor(String type) {
    return getGlobalObject().getPrototypeFor(type);
  }

  public String toString() {
    return "ExecutionContext: " + System.identityHashCode(this) + "; parent=" + this.parent;
  }

  public DynamicClassLoader getClassLoader() {
    return getRuntime().getConfig().getClassLoader();
  }
}