module Inference
  ( infer
  ) where

import           Data.Generics.Product.Typed
import qualified Data.Map.Strict             as Map
import           Data.Partition              (Partition)
import qualified Data.Partition              as Disj
import qualified Data.Set                    as Set

import qualified Builtins
import           Error
import           Syntax
import           Types

data Inferring

type instance Context Inferring = Metavar

metavar :: (Tree t Inferring) => t Inferring -> Metavar
metavar = context

metatype :: (Tree t Inferring) => t Inferring -> Type
metatype = Metavariable . context

data Inferred

type instance Context Inferred = Type

infer :: Term' phase -> Either InferenceError (Term' Inferred, State)
infer =
  runExcept . usingStateT def . (applySolution <=< solveAST <=< assignMetavars)

type MonadInferenceState m = MonadState State m

type MonadInferenceError m = MonadError InferenceError m

type MonadScope m = MonadReader Scope m

type MonadInfer m = (MonadInferenceState m, MonadInferenceError m)

data State = State
  { nextMetavariable :: Metavar
  , solution         :: Solution
  , equivalences     :: Partition Metavar
  } deriving (Show, Eq, Generic)

instance Default State where
  def =
    State
      {nextMetavariable = MV 0, solution = Map.empty, equivalences = Disj.empty}

data Scope = Scope
  { current  :: Term' Inferring
  , bindings :: Bindings
  } deriving (Show, Eq, Generic)

type Bindings = Map Binding Type

lookupBinding :: (MonadScope m, MonadInferenceError m) => Binding -> m Type
lookupBinding binding = do
  env <- view (typed @Bindings)
  maybe (throwError $ UnknownBinding binding) pure $ Map.lookup binding env

type Solution = Map Metavar Type

lookupSolution :: (MonadInfer m) => Metavar -> m (Maybe Type)
lookupSolution mv = do
  env <- use (typed @Solution)
  mvs <- siblings mv
  let result = asum $ map (flip Map.lookup env) (mv : mvs)
  pure result

insertSolution :: (MonadState State m) => Metavar -> Type -> m ()
insertSolution mv typ = modifying (typed @Solution) $ Map.insert mv typ

combineMetavars :: (MonadState State m) => Metavar -> Metavar -> m ()
combineMetavars mv1 mv2 =
  modifying (typed @(Partition Metavar)) $ Disj.joinElems mv1 mv2

freshMetavar :: (MonadInferenceState m) => m Metavar
freshMetavar = do
  mv <- use (typed @Metavar)
  modifying (typed @Metavar) succ
  pure mv

assignMetavars :: (MonadInferenceState m) => Term' phase -> m (Term' Inferring)
assignMetavars = Syntax.metaM $ const freshMetavar

metavarHere :: (MonadScope m) => m Metavar
metavarHere = context <$> asks current

typeHere :: (MonadScope m) => m Type
typeHere = Metavariable <$> metavarHere

solveAST :: (MonadInfer m) => Term' Inferring -> m (Term' Inferring)
solveAST term = do
  usingReaderT
    (Scope {current = term, bindings = map Syntax.typ Builtins.builtins}) $
    solveNode term
  pure term

solveNode :: (MonadInfer m, MonadScope m) => Term' Inferring -> m ()
solveNode (ESymbol _ binding) = do
  updateHere =<< lookupBinding binding
solveNode (EApplication _ function arguments) = do
  returnType <- typeHere
  updateHere returnType
  let argumentTypes = map metatype arguments
      functionType = Types.fn $ argumentTypes <> [returnType]
      functionMV = metavar function
  update functionMV functionType
  local (set #current function) $ solveNode function
  traverse_ (\arg -> local (set #current arg) $ solveNode arg) arguments
solveNode (EIf _ test thn els) = do
  let testMV = metavar test
  update testMV (Primitive Boolean)
  traverse_ (updateHere . metatype) [thn, els]
  traverse_
    (\term -> local (set #current term) $ solveNode term)
    [test, thn, els]
solveNode (EMatch _ proto clauses) = do
  let protoMV = metavar proto
  local (set #current proto) $ solveNode proto
  forM_
    clauses
    (\(pat, body) -> do
       (typ, newBindings) <- patternType pat
       update (metavar pat) typ
       update protoMV typ
       updateHere (metatype body)
       local (set #current body . over #bindings (`mappend` newBindings)) $
         solveNode body)
solveNode (EAtom _ atom) = updateHere (Primitive $ primitiveType atom)
solveNode (ELet _ bindings body) = do
  updateHere $ metatype body
  let newBindings = Map.fromList $ map (fst &&& (metatype . snd)) bindings
  traverse_ (\(_, term) -> local (set #current term) $ solveNode term) bindings
  local (set #current body . over #bindings (`mappend` newBindings)) $
    solveNode body

unify' :: (MonadInfer m) => Bool -> Type -> Type -> m Type
unify' _ t@(Primitive pt1) (Primitive pt2)
  | pt1 == pt2 = pure t
unify' _ (Function arg1 ret1) (Function arg2 ret2) = do
  arg <- unify' True arg1 arg2
  ret <- unify' True ret1 ret2
  pure $ Function arg ret
unify' _ (Metavariable mv1) (Metavariable mv2) = do
  combineMetavars mv1 mv2
  fromMaybe (Metavariable (min mv1 mv2)) <$> lookupSolution mv2
unify' prop (Metavariable mv) t2 = do
  current <- lookupSolution mv
  new <-
    case current of
      Nothing -> pure t2
      Just tc -> unify' True t2 tc
  insertSolution mv new
  when prop $ traverse_ (unify' False t2 . Metavariable) =<< siblings mv
  pure new
unify' prop t mv@(Metavariable _) = unify' prop mv t
unify' _ t1 t2 = throwError $ UnificationFailure t1 t2

unify :: (MonadInfer m) => Type -> Type -> m Type
unify = unify' True

update :: (MonadInfer m) => Metavar -> Type -> m ()
update mv typ = void $ unify (Metavariable mv) typ

updateHere :: (MonadInfer m, MonadScope m) => Type -> m ()
updateHere typ = do
  mv <- metavarHere
  update mv typ

siblings :: (MonadInferenceState m) => Metavar -> m [Metavar]
siblings mv = do
  State {equivalences} <- get
  pure $ Set.toList $ Set.delete mv $ Disj.find equivalences mv

-- representative :: (MonadState State m) => Metavar -> m Metavar
-- representative mv = do
--   State{equivalences} <- get
--   pure $ Disj.rep equivalences mv
applySolution :: (MonadInfer m) => Term' Inferring -> m (Term' Inferred)
applySolution =
  Syntax.metaM $ \mv -> liftMaybe (UnknownVariable mv) =<< lookupSolution mv

primitiveType :: Syntax.Atom -> PrimitiveType
primitiveType =
  \case
    AUnit -> Unit
    AInteger _ -> Integer
    AString _ -> String
    ABoolean _ -> Boolean

patternType ::
     (MonadInfer m) => Pattern' Inferring -> m (Type, Map Binding Type)
patternType (PAtom _ atom) = pure (Primitive $ primitiveType atom, mempty)
patternType pat@(PWildcard _) = pure (metatype pat, mempty)
patternType pat@(PSymbol _ binding) =
  let typ = metatype pat
   in pure (typ, Map.singleton binding typ)
patternType pat@(PVector _ ps) =
  foldM
    (\(t, env) p -> do
       (t', env') <- patternType p
       (,) <$> unify t t' <*> (pure $ env <> env'))
    (metatype pat, mempty)
    ps
