// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/Analyzer.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.View;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.IdGenerator;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repository of analysis state for single select block.
 * <p/>
 * All conjuncts are assigned a unique id when initially registered, and all
 * registered conjuncts are referenced by their id (ie, there are no containers
 * other than the one holding the referenced conjuncts), to make substitute()
 * simple.
 */
@Deprecated
public class Analyzer {
    private static final Logger LOG = LogManager.getLogger(Analyzer.class);

    // NOTE: Alias of table is case sensitive
    // UniqueAlias used to check wheather the table ref or the alias is unique
    // table/view used db.table, inline use alias
    private final Set<String> uniqueTableAliasSet_ = Sets.newHashSet();
    private final Multimap<String, TupleDescriptor> tupleByAlias = ArrayListMultimap.create();

    // NOTE: Alias of column is case ignorance
    // map from lowercase table alias to descriptor.
    // protected final Map<String, TupleDescriptor> aliasMap             = Maps.newHashMap();
    // map from lowercase qualified column name ("alias.col") to descriptor
    private final Map<String, SlotDescriptor> slotRefMap = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);

    // map from tuple id to list of conjuncts referencing tuple
    private final Map<TupleId, List<ExprId>> tuplePredicates = Maps.newHashMap();
    // map from slot id to list of conjuncts referencing slot
    private final Map<SlotId, List<ExprId>> slotPredicates = Maps.newHashMap();
    // eqJoinPredicates[tid] contains all conjuncts of the form
    // "<lhs> = <rhs>" in which either lhs or rhs is fully bound by tid
    // and the other side is not bound by tid (ie, predicates that express equi-join
    // conditions between two tablerefs).
    // A predicate such as "t1.a = t2.b" has two entries, one for 't1' and
    // another one for 't2'.

    // map from tuple id to the current output column index
    private final Map<TupleId, Integer> currentOutputColumn = Maps.newHashMap();

    // Current depth of nested analyze() calls. Used for enforcing a
    // maximum expr-tree depth. Needs to be manually maintained by the user
    // of this Analyzer with incrementCallDepth() and decrementCallDepth().
    private int callDepth = 0;

    // Flag indicating if this analyzer instance belongs to a subquery.
    private boolean isSubquery = false;

    // Flag indicating whether this analyzer belongs to a WITH clause view.
    private boolean isWithClause_ = false;

    // By default, all registered semi-joined tuples are invisible, i.e., their slots
    // cannot be referenced. If set, this semi-joined tuple is made visible. Such a tuple
    // should only be made visible for analyzing the On-clause of its semi-join.
    // In particular, if there are multiple semi-joins in the same query block, then the
    // On-clause of any such semi-join is not allowed to reference other semi-joined tuples
    // except its own. Therefore, only a single semi-joined tuple can be visible at a time.
    private TupleId visibleSemiJoinedTupleId_ = null;
    // for some situation that udf is not allowed.
    private boolean isUDFAllowed = true;
    // timezone specified for some operation, such as broker load
    private String timezone = TimeUtils.DEFAULT_TIME_ZONE;

    // Whether to ignore cast expressions
    // Compatibility with older versions, maybe delete in near future
    private boolean ignoreCast = false;
    private String schemaDb;
    private String schemaTable;
    private String schemaWild;

    public boolean isWithClause() {
        return isWithClause_;
    }

    public void setUDFAllowed(boolean val) {
        this.isUDFAllowed = val;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getTimezone() {
        return timezone;
    }

    // state shared between all objects of an Analyzer tree
    // TODO: Many maps here contain properties about tuples, e.g., whether
    // a tuple is outer/semi joined, etc. Remove the maps in favor of making
    // them properties of the tuple descriptor itself.
    private static class GlobalState {
        private final DescriptorTable descTbl = new DescriptorTable();
        private final GlobalStateMgr globalStateMgr;
        private final IdGenerator<ExprId> conjunctIdGenerator = ExprId.createGenerator();
        private final ConnectContext context;

        // True if we are analyzing an explain request. Should be set before starting
        // analysis.
        public boolean isExplain;

        // all registered conjuncts (map from id to Predicate)
        private final Map<ExprId, Expr> conjuncts = Maps.newHashMap();

        // eqJoinConjuncts[tid] contains all conjuncts of the form
        // "<lhs> = <rhs>" in which either lhs or rhs is fully bound by tid
        // and the other side is not bound by tid (ie, predicates that express equi-join
        // conditions between two tablerefs).
        // A predicate such as "t1.a = t2.b" has two entries, one for 't1' and
        // another one for 't2'.
        private final Map<TupleId, List<ExprId>> eqJoinConjuncts = Maps.newHashMap();

        // set of conjuncts that have been assigned to some PlanNode
        private final Set<ExprId> assignedConjuncts =
                Collections.newSetFromMap(new IdentityHashMap<ExprId, Boolean>());

        // map from outer-joined tuple id, ie, one that is nullable in this select block,
        // to the last Join clause (represented by its rhs table ref) that outer-joined it
        private final Map<TupleId, TableRef> outerJoinedTupleIds = Maps.newHashMap();

        // Map of registered conjunct to the last full outer join (represented by its
        // rhs table ref) that outer joined it.
        public final Map<ExprId, TableRef> fullOuterJoinedConjuncts = Maps.newHashMap();

        // Map of full-outer-joined tuple id to the last full outer join that outer-joined it
        public final Map<TupleId, TableRef> fullOuterJoinedTupleIds = Maps.newHashMap();

        // Map from semi-joined tuple id, i.e., one that is invisible outside the join's
        // On-clause, to its Join clause (represented by its rhs table ref). An anti-join is
        // a kind of semi-join, so anti-joined tuples are also registered here.
        public final Map<TupleId, TableRef> semiJoinedTupleIds = Maps.newHashMap();

        // Map from right-hand side table-ref id of an outer join to the list of
        // conjuncts in its On clause. There is always an entry for an outer join, but the
        // corresponding value could be an empty list. There is no entry for non-outer joins.
        public final Map<TupleId, List<ExprId>> conjunctsByOjClause = Maps.newHashMap();

        // map from registered conjunct to its containing outer join On clause (represented
        // by its right-hand side table ref); only conjuncts that can only be correctly
        // evaluated by the originating outer join are registered here
        private final Map<ExprId, TableRef> ojClauseByConjunct = Maps.newHashMap();

        // map from registered conjunct to its containing semi join On clause (represented
        // by its right-hand side table ref)
        public final Map<ExprId, TableRef> sjClauseByConjunct = Maps.newHashMap();

        // map from registered conjunct to its containing inner join On clause (represented
        // by its right-hand side table ref)
        public final Map<ExprId, TableRef> ijClauseByConjunct = Maps.newHashMap();

        // TODO chenhao16, to save conjuncts, which children are constant
        public final Map<TupleId, Set<ExprId>> constantConjunct = Maps.newHashMap();

        // map from slot id to the analyzer/block in which it was registered
        public final Map<SlotId, Analyzer> blockBySlot = Maps.newHashMap();

        public GlobalState(GlobalStateMgr globalStateMgr, ConnectContext context) {
            this.globalStateMgr = globalStateMgr;
            this.context = context;
        }
    }

    private final GlobalState globalState;

    // An analyzer stores analysis state for a single select block. A select block can be
    // a top level select statement, or an inline view select block.
    // ancestors contains the Analyzers of the enclosing select blocks of 'this'
    // (ancestors[0] contains the immediate parent, etc.).
    private final ArrayList<Analyzer> ancestors;

    // map from lowercase table alias to a view definition in this analyzer's scope
    private final Map<String, View> localViews_ = Maps.newHashMap();

    // Map from lowercase table alias to descriptor. Tables without an explicit alias
    // are assigned two implicit aliases: the unqualified and fully-qualified table name.
    // Such tables have two entries pointing to the same descriptor. If an alias is
    // ambiguous, then this map retains the first entry with that alias to simplify error
    // checking (duplicate vs. ambiguous alias).
    private final Map<String, TupleDescriptor> aliasMap_ = Maps.newHashMap();

    // Map from tuple id to its corresponding table ref.
    private final Map<TupleId, TableRef> tableRefMap_ = Maps.newHashMap();

    // Set of lowercase ambiguous implicit table aliases.
    private final Set<String> ambiguousAliases_ = Sets.newHashSet();

    // Indicates whether this analyzer/block is guaranteed to have an empty result set
    // due to a limit 0 or constant conjunct evaluating to false.
    private boolean hasEmptyResultSet_ = false;

    // Indicates whether the select-project-join (spj) portion of this query block
    // is guaranteed to return an empty result set. Set due to a constant non-Having
    // conjunct evaluating to false.
    private boolean hasEmptySpjResultSet_ = false;

    public Analyzer(GlobalStateMgr globalStateMgr, ConnectContext context) {
        ancestors = Lists.newArrayList();
        globalState = new GlobalState(globalStateMgr, context);
    }

    /**
     * Analyzer constructor for nested select block. GlobalStateMgr and DescriptorTable
     * is inherited from the parentAnalyzer.
     *
     * @param parentAnalyzer the analyzer of the enclosing select block
     */
    public Analyzer(Analyzer parentAnalyzer) {
        this(parentAnalyzer, parentAnalyzer.globalState);
        if (parentAnalyzer.isSubquery) {
            this.isSubquery = true;
        }
    }

    /**
     * Analyzer constructor for nested select block with the specified global state.
     */
    private Analyzer(Analyzer parentAnalyzer, GlobalState globalState) {
        ancestors = Lists.newArrayList(parentAnalyzer);
        ancestors.addAll(parentAnalyzer.ancestors);
        this.globalState = globalState;
    }

    public void setIsExplain() {
        globalState.isExplain = true;
    }

    public boolean isExplain() {
        return globalState.isExplain;
    }

    public int incrementCallDepth() {
        return ++callDepth;
    }

    public int decrementCallDepth() {
        return --callDepth;
    }

    public int getCallDepth() {
        return callDepth;
    }

    /**
     * Substitute analyzer's internal expressions (conjuncts) with the given
     * substitution map
     */
    public void substitute(ExprSubstitutionMap sMap) {
        for (ExprId id : globalState.conjuncts.keySet()) {
            // TODO(dhc): next three lines for subquery
            if (globalState.conjuncts.get(id).substitute(sMap) instanceof BoolLiteral) {
                continue;
            }
            globalState.conjuncts.put(id, (Predicate) globalState.conjuncts.get(id).substitute(sMap));
        }
    }

    /**
     * Creates an returns an empty TupleDescriptor for the given table ref and registers
     * it against all its legal aliases. For tables refs with an explicit alias, only the
     * explicit alias is legal. For tables refs with no explicit alias, the fully-qualified
     * and unqualified table names are legal aliases. Column references against unqualified
     * implicit aliases can be ambiguous, therefore, we register such ambiguous aliases
     * here. Requires that all views have been substituted.
     * Throws if an existing explicit alias or implicit fully-qualified alias
     * has already been registered for another table ref.
     */
    public TupleDescriptor registerTableRef(TableRef ref) throws AnalysisException {
        String uniqueAlias = ref.getUniqueAlias();
        if (uniqueTableAliasSet_.contains(uniqueAlias)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_NONUNIQ_TABLE, uniqueAlias);
        }
        uniqueTableAliasSet_.add(uniqueAlias);

        // If ref has no explicit alias, then the unqualified and the fully-qualified table
        // names are legal implicit aliases. Column references against unqualified implicit
        // aliases can be ambiguous, therefore, we register such ambiguous aliases here.
        String unqualifiedAlias = null;
        String[] aliases = ref.getAliases();
        if (aliases.length > 1) {
            unqualifiedAlias = aliases[1];
            TupleDescriptor tupleDesc = aliasMap_.get(unqualifiedAlias);
            if (tupleDesc != null) {
                if (tupleDesc.hasExplicitAlias()) {
                    ErrorReport.reportAnalysisException(ErrorCode.ERR_NONUNIQ_TABLE, uniqueAlias);
                } else {
                    ambiguousAliases_.add(unqualifiedAlias);
                }
            }
        }

        // Delegate creation of the tuple descriptor to the concrete table ref.
        TupleDescriptor result = ref.createTupleDescriptor(this);
        result.setRef(ref);
        result.setAliases(aliases, ref.hasExplicitAlias());

        // Register all legal aliases.
        for (String alias : aliases) {
            // TODO(zc)
            // aliasMap_.put(alias, result);
            tupleByAlias.put(alias, result);
        }
        tableRefMap_.put(result.getId(), ref);

        return result;
    }

    /**
     * Resolves the given TableRef into a concrete BaseTableRef, ViewRef or
     * CollectionTableRef. Returns the new resolved table ref or the given table
     * ref if it is already resolved.
     * Registers privilege requests and throws an AnalysisException if the tableRef's
     * path could not be resolved. The privilege requests are added to ensure that
     * an AuthorizationException is preferred over an AnalysisException so as not to
     * accidentally reveal the non-existence of tables/databases.
     * <p>
     * TODO(zc): support collection table ref
     */
    public TableRef resolveTableRef(TableRef tableRef) throws AnalysisException {
        // Return the table if it is already resolved.
        if (tableRef.isResolved()) {
            return tableRef;
        }
        // Try to find a matching local view.
        TableName tableName = tableRef.getName();
        if (!tableName.isFullyQualified()) {
            // Searches the hierarchy of analyzers bottom-up for a registered local view with
            // a matching alias.
            String viewAlias = tableName.getTbl();
            Analyzer analyzer = this;
            do {
                View localView = analyzer.localViews_.get(viewAlias);
                if (localView != null) {
                    return new InlineViewRef(localView, tableRef);
                }
                analyzer = (analyzer.ancestors.isEmpty() ? null : analyzer.ancestors.get(0));
            } while (analyzer != null);
        }

        // Resolve the table ref's path and determine what resolved table ref
        // to replace it with.
        String dbName = tableName.getDb();
        if (Strings.isNullOrEmpty(dbName)) {
            dbName = getDefaultDb();
        } else {
            dbName = ClusterNamespace.getFullName(tableName.getDb());
        }
        if (Strings.isNullOrEmpty(dbName)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_DB_ERROR);
        }

        Database database = globalState.globalStateMgr.getDb(dbName);
        if (database == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        Table table = database.getTable(tableName.getTbl());
        if (table == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName.getTbl());
        }

        if (table.getType() == TableType.OLAP && (((OlapTable) table).getState() == OlapTableState.RESTORE
                || ((OlapTable) table).getState() == OlapTableState.RESTORE_WITH_LOAD)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_STATE, "RESTORING");
        }

        TableName tblName = new TableName(database.getFullName(), table.getName());
        if (table instanceof View) {
            return new InlineViewRef((View) table, tableRef);
        } else {
            // The table must be a base table.
            return new BaseTableRef(tableRef, table, tblName);
        }
    }

    public Table getTable(TableName tblName) {
        Database db = globalState.globalStateMgr.getDb(tblName.getDb());
        if (db == null) {
            return null;
        }
        return db.getTable(tblName.getTbl());
    }

    /**
     * Return descriptor of registered table/alias.
     *
     * @param name
     * @return null if not registered.
     */
    public Collection<TupleDescriptor> getDescriptor(TableName name) {
        return tupleByAlias.get(name.toString());
    }

    public TupleDescriptor getTupleDesc(TupleId id) {
        return globalState.descTbl.getTupleDesc(id);
    }

    /**
     * Register a virtual column, and it is not a real column exist in table,
     * so it does not need to resolve.
     */
    public SlotDescriptor registerVirtualColumnRef(String colName, Type type, TupleDescriptor tupleDescriptor)
            throws AnalysisException {
        // Make column name case insensitive
        String key = colName;
        SlotDescriptor result = slotRefMap.get(key);
        if (result != null) {
            result.setMultiRef(true);
            return result;
        }

        result = addSlotDescriptor(tupleDescriptor);
        Column col = new Column(colName, type);
        result.setColumn(col);
        result.setIsNullable(true);
        slotRefMap.put(key, result);
        return result;
    }

    /**
     * Creates a new slot descriptor and related state in globalState.
     */
    public SlotDescriptor addSlotDescriptor(TupleDescriptor tupleDesc) {
        SlotDescriptor result = globalState.descTbl.addSlotDescriptor(tupleDesc);
        globalState.blockBySlot.put(result.getId(), this);
        return result;
    }

    /**
     * Return all unassigned registered conjuncts that are fully bound by the given
     * (logical) tuple ids, can be evaluated by 'tupleIds' and are not tied to an
     * Outer Join clause.
     */
    public List<Expr> getUnassignedConjuncts(List<TupleId> tupleIds) {
        List<Expr> result = Lists.newArrayList();
        for (Expr e : getUnassignedConjuncts(tupleIds, true)) {
            if (canEvalPredicate(tupleIds, e)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Return all unassigned non-constant registered conjuncts that are fully bound by
     * given list of tuple ids. If 'inclOjConjuncts' is false, conjuncts tied to an
     * Outer Join clause are excluded.
     */
    public List<Expr> getUnassignedConjuncts(
            List<TupleId> tupleIds, boolean inclOjConjuncts) {
        List<Expr> result = Lists.newArrayList();
        for (Expr e : globalState.conjuncts.values()) {
            // handle constant conjuncts
            if (e.isConstant()) {
                boolean isBoundByTuple = false;
                for (TupleId id : tupleIds) {
                    final Set<ExprId> exprSet = globalState.constantConjunct.get(id);
                    if (exprSet != null && exprSet.contains(e.id)) {
                        isBoundByTuple = true;
                        break;
                    }
                }
                if (!isBoundByTuple) {
                    continue;
                }
            }
            if (e.isBoundByTupleIds(tupleIds)
                    && !e.isAuxExpr()
                    && !globalState.assignedConjuncts.contains(e.getId())
                    && ((inclOjConjuncts && !e.isConstant())
                    || !globalState.ojClauseByConjunct.containsKey(e.getId()))) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Returns the fully-qualified table name of tableName. If tableName
     * is already fully qualified, returns tableName.
     */
    public TableName getFqTableName(TableName tableName) {
        if (tableName.isFullyQualified()) {
            return tableName;
        }
        return new TableName(getDefaultDb(), tableName.getTbl());
    }

    /**
     * Return rhs ref of last Join clause that outer-joined id.
     */
    public TableRef getLastOjClause(TupleId id) {
        return globalState.outerJoinedTupleIds.get(id);
    }

    public boolean isSemiJoined(TupleId tid) {
        return globalState.semiJoinedTupleIds.containsKey(tid);
    }

    public boolean isAntiJoinedConjunct(Expr e) {
        return getAntiJoinRef(e) != null;
    }

    public TableRef getAntiJoinRef(Expr e) {
        TableRef tblRef = globalState.sjClauseByConjunct.get(e.getId());
        if (tblRef == null) {
            return null;
        }
        return (tblRef.getJoinOp().isAntiJoin()) ? tblRef : null;
    }

    public boolean containsOuterJoinedTid(Set<TupleId> tids) {
        for (TupleId tid : tids) {
            if (isOuterJoined(tid)) {
                return true;
            }
        }
        return false;
    }

    public DescriptorTable getDescTbl() {
        return globalState.descTbl;
    }

    public GlobalStateMgr getCatalog() {
        return globalState.globalStateMgr;
    }

    public Set<String> getAliases() {
        return uniqueTableAliasSet_;
    }

    public void setHasEmptyResultSet() {
        hasEmptyResultSet_ = true;
    }

    public boolean isOjConjunct(Expr e) {
        return globalState.ojClauseByConjunct.containsKey(e.getId());
    }

    public boolean isIjConjunct(Expr e) {
        return globalState.ijClauseByConjunct.containsKey(e.getId());
    }

    public boolean isSjConjunct(Expr e) {
        return globalState.sjClauseByConjunct.containsKey(e.getId());
    }

    public TableRef getFullOuterJoinRef(Expr e) {
        return globalState.fullOuterJoinedConjuncts.get(e.getId());
    }

    public boolean isFullOuterJoined(Expr e) {
        return globalState.fullOuterJoinedConjuncts.containsKey(e.getId());
    }

    /**
     * return equal conjuncts, used by OlapScanNode.normalizePredicate and SelectStmt.reorderTable
     */
    public List<Expr> getEqJoinConjuncts(TupleId id) {
        final List<ExprId> conjunctIds = globalState.eqJoinConjuncts.get(id);
        if (conjunctIds == null) {
            return Lists.newArrayList();
        }
        final List<Expr> result = Lists.newArrayList();
        for (ExprId conjunctId : conjunctIds) {
            final Expr e = globalState.conjuncts.get(conjunctId);
            Preconditions.checkState(e != null);
            result.add(e);
        }
        return result;
    }

    public int getCurrentOutputColumn(TupleId id) {
        Integer result = currentOutputColumn.get(id);
        if (null == result) {
            return this.getTupleDesc(id).getSlots().size();
        }
        return result;
    }

    public void setCurrentOutputColumn(TupleId id, int v) {
        currentOutputColumn.put(id, v);
    }

    /**
     * Mark predicates as assigned.
     */
    public void markConjunctsAssigned(List<Expr> conjuncts) {
        if (conjuncts == null) {
            return;
        }
        for (Expr p : conjuncts) {
            globalState.assignedConjuncts.add(p.getId());
        }
    }

    /**
     * Returns assignment-compatible type of expr.getType() and lastCompatibleType.
     * If lastCompatibleType is null, returns expr.getType() (if valid).
     * If types are not compatible throws an exception reporting
     * the incompatible types and their expr.toSql().
     * <p>
     * lastCompatibleExpr is passed for error reporting purposes,
     * but note that lastCompatibleExpr may not yet have lastCompatibleType,
     * because it was not cast yet.
     */
    public Type getCompatibleType(Type lastCompatibleType, Expr lastCompatibleExpr, Expr expr)
            throws AnalysisException {
        Type newCompatibleType;
        if (lastCompatibleType == null) {
            newCompatibleType = expr.getType();
        } else {
            newCompatibleType = Type.getAssignmentCompatibleType(lastCompatibleType, expr.getType(), false);
        }
        if (!newCompatibleType.isValid()) {
            throw new AnalysisException(String.format(
                    "Incompatible return types '%s' and '%s' of exprs '%s' and '%s'.",
                    lastCompatibleType.toSql(), expr.getType().toSql(),
                    lastCompatibleExpr.toSql(), expr.toSql()));
        }
        return newCompatibleType;
    }

    /**
     * Determines compatible type for given exprs, and casts them to compatible
     * type. Calls analyze() on each of the exprs. Throw an AnalysisException if
     * the types are incompatible, returns compatible type otherwise.
     */
    public Type castAllToCompatibleType(List<Expr> exprs) throws AnalysisException {
        // Determine compatible type of exprs.
        exprs.get(0).analyze(this);
        Type compatibleType = exprs.get(0).getType();
        for (int i = 1; i < exprs.size(); ++i) {
            exprs.get(i).analyze(this);
            // TODO(zc)
            compatibleType = Type.getCmpType(compatibleType, exprs.get(i).getType());
        }
        if (compatibleType.isVarchar()) {
            if (exprs.get(0).getType().isDateType()) {
                compatibleType = exprs.get(0).getType();
            }
        }

        // In general, decimal32 is casted into decimal64 before processed, but
        // decimal32-typed predicates keep decimal32-typed SlotRef unchanged so that
        // BE can push these predicates down to ColumnReader for speedup.
        if (compatibleType.getPrimitiveType() == PrimitiveType.DECIMAL64) {
            if (exprs.get(0).getType().getPrimitiveType() == PrimitiveType.DECIMAL32) {
                compatibleType = exprs.get(0).getType();
            }
        }
        // Add implicit casts if necessary.
        for (int i = 0; i < exprs.size(); ++i) {
            if (!exprs.get(i).getType().equals(compatibleType)) {
                Expr castExpr = exprs.get(i).castTo(compatibleType);
                exprs.set(i, castExpr);
            }
        }
        return compatibleType;
    }

    public String getDefaultDb() {
        return globalState.context.getDatabase();
    }

    public String getDefaultCatalog() {
        return globalState.context.getCurrentCatalog();
    }

    public String getClusterName() {
        return globalState.context.getClusterName();
    }

    public String getQualifiedUser() {
        return globalState.context.getQualifiedUser();
    }

    public ConnectContext getContext() {
        return globalState.context;
    }

    /**
     * Returns true if predicate 'e' can be correctly evaluated by a tree materializing
     * 'tupleIds', otherwise false:
     * - The predicate needs to be bound by tupleIds.
     * - For On-clause predicates:
     * - If the predicate is from an anti-join On-clause it must be evaluated by the
     * corresponding anti-join node.
     * - Predicates from the On-clause of an inner or semi join are evaluated at the
     * node that materializes the required tuple ids, unless they reference outer
     * joined tuple ids. In that case, the predicates are evaluated at the join node
     * of the corresponding On-clause.
     * - Predicates referencing full-outer joined tuples are assigned at the originating
     * join if it is a full-outer join, otherwise at the last full-outer join that does
     * not materialize the table ref ids of the originating join.
     * - Predicates from the On-clause of a left/right outer join are assigned at
     * the corresponding outer join node with the exception of simple predicates
     * that only reference a single tuple id. Those may be assigned below the
     * outer join node if they are from the same On-clause that makes the tuple id
     * nullable.
     * - Otherwise, a predicate can only be correctly evaluated if for all outer-joined
     * referenced tids the last join to outer-join this tid has been materialized.
     */
    public boolean canEvalPredicate(List<TupleId> tupleIds, Expr e) {
        if (!e.isBoundByTupleIds(tupleIds)) {
            return false;
        }
        ArrayList<TupleId> ids = Lists.newArrayList();
        e.getIds(ids, null);
        Set<TupleId> tids = Sets.newHashSet(ids);
        if (tids.isEmpty()) {
            return true;
        }

        if (e.isOnClauseConjunct()) {

            if (isAntiJoinedConjunct(e)) {
                return canEvalAntiJoinedConjunct(e, tupleIds);
            }
            if (isIjConjunct(e) || isSjConjunct(e)) {
                if (!containsOuterJoinedTid(tids)) {
                    return true;
                }
                // If the predicate references an outer-joined tuple, then evaluate it at
                // the join that the On-clause belongs to.
                TableRef onClauseTableRef = null;
                if (isIjConjunct(e)) {
                    onClauseTableRef = globalState.ijClauseByConjunct.get(e.getId());
                } else {
                    onClauseTableRef = globalState.sjClauseByConjunct.get(e.getId());
                }
                Preconditions.checkNotNull(onClauseTableRef);
                return tupleIds.containsAll(onClauseTableRef.getAllTableRefIds());
            }

            if (isFullOuterJoined(e)) {
                return canEvalFullOuterJoinedConjunct(e, tupleIds);
            }
            if (isOjConjunct(e)) {
                // Force this predicate to be evaluated by the corresponding outer join node.
                // The join node will pick up the predicate later via getUnassignedOjConjuncts().
                if (tids.size() > 1) {
                    return false;
                }
                // Optimization for single-tid predicates: Legal to assign below the outer join
                // if the predicate is from the same On-clause that makes tid nullable
                // (otherwise e needn't be true when that tuple is set).
                TupleId tid = tids.iterator().next();
                return globalState.ojClauseByConjunct.get(e.getId()) == getLastOjClause(tid);
            }
        }

        for (TupleId tid : tids) {
            TableRef rhsRef = getLastOjClause(tid);
            // this is not outer-joined; ignore
            if (rhsRef == null) {
                continue;
            }
            // check whether the last join to outer-join 'tid' is materialized by tupleIds
            if (!tupleIds.containsAll(rhsRef.getAllTupleIds())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a conjunct from the On-clause of an anti join can be evaluated in a node
     * that materializes a given list of tuple ids.
     */
    public boolean canEvalAntiJoinedConjunct(Expr e, List<TupleId> nodeTupleIds) {
        TableRef antiJoinRef = getAntiJoinRef(e);
        if (antiJoinRef == null) {
            return true;
        }
        List<TupleId> tids = Lists.newArrayList();
        e.getIds(tids, null);
        if (tids.size() > 1) {
            return nodeTupleIds.containsAll(antiJoinRef.getAllTableRefIds())
                    && antiJoinRef.getAllTableRefIds().containsAll(nodeTupleIds);
        }
        // A single tid conjunct that is anti-joined can be safely assigned to a
        // node below the anti join that specified it.
        return globalState.semiJoinedTupleIds.containsKey(tids.get(0));
    }

    /**
     * Returns false if 'e' references a full outer joined tuple and it is incorrect to
     * evaluate 'e' at a node materializing 'tids'. Returns true otherwise.
     */
    public boolean canEvalFullOuterJoinedConjunct(Expr e, List<TupleId> tids) {
        TableRef fullOuterJoin = getFullOuterJoinRef(e);
        if (fullOuterJoin == null) {
            return true;
        }
        return tids.containsAll(fullOuterJoin.getAllTableRefIds());
    }

    public boolean isOuterJoined(TupleId tid) {
        return globalState.outerJoinedTupleIds.containsKey(tid);
    }
}
