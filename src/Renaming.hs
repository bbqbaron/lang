module Renaming
  ( rename
  , renameWith
  , Renaming
  )
where

import qualified Data.Map.Strict               as Map

import           Builtins
import           Classes
import           Error
import qualified Syntax.Common                 as Common
import qualified Syntax.Definition             as Definition
import qualified Syntax.Term                   as Term
import qualified Syntax.Type                   as Type


data Renaming

type instance Context Renaming = Maybe Binding

type Term = Term.Term Renaming
type Definition = Definition.Definition Renaming
type Binding = Common.Binding

type MonadRename m = (MonadError RenamingError m, MonadState Env m)

type Env = Map Binding Int

rename :: (Renameable t) => t phase -> Either Error (t Renaming)
rename = renameWith $ map (const 0) builtins

renameWith :: (Renameable t) => Env -> t phase -> Either Error (t Renaming)
renameWith env = runRenaming env . renameNode

runRenaming :: Env -> StateT Env (Except RenamingError) a -> Either Error a
runRenaming env = runExcept . withExcept Renaming . evaluatingStateT env

alias :: (MonadRename m) => Binding -> m Binding
alias binding@(Common.Single name) = do
  env <- get
  pure . Common.Single $ case Map.lookup binding env of
    Nothing -> name
    Just 0  -> name
    Just i  -> name <> show i

insertName :: Binding -> Map Binding Int -> Map Binding Int
insertName name = Map.insertWith (\old new -> old + new + 1) name 0

add :: (MonadRename m) => Binding -> m a -> m a
add binding action = do
  modify $ insertName binding
  action

adds :: (MonadRename m) => [Binding] -> m a -> m a
adds names action = do
  traverse_ (modify . insertName) names
  action

check :: (MonadRename m) => Binding -> m ()
check binding@(Common.Single name) = do
  env <- get
  case Map.lookup binding env of
    Just _  -> pass
    Nothing -> throwError (UnknownSymbol name)

class Renameable t where
  renameNode :: (MonadRename m) => t phase -> m (t Renaming)

instance Renameable Term.Term where
  renameNode = renameTerm

renameTerm :: (MonadRename m) => Term.Term phase -> m Term
renameTerm (Term.Symbol _ name) = do
  check name
  name' <- alias name
  pure $ Term.Symbol (Just name) name'
renameTerm (Term.Lambda _ arg body) = add arg $ do
  arg'  <- alias arg
  body' <- renameTerm body
  pure $ Term.Lambda Nothing arg' body'
renameTerm (Term.Let _ bindings body) = do
  let go ((name, value) : moreBindings) = do
        value' :: Term <- renameTerm value
        add name $ do
          name'                   <- alias name
          (moreBindings', result) <- go moreBindings
          pure ((name', value') : moreBindings', result)
      go [] = sequenceA ([], renameTerm body)
  (bindings', body') <- go bindings
  pure $ Term.Let Nothing bindings' body'
renameTerm (Term.Application _ fn args) = do
  fn'   <- renameTerm fn
  args' <- traverse renameTerm args
  pure $ Term.Application Nothing fn' args'
renameTerm term = metaM (const $ pure Nothing) term

instance Renameable Definition.Definition  where
  renameNode = renameDefinition

renameDefinition :: (MonadRename m) => Definition.Definition phase -> m Definition
renameDefinition (Definition.Module _ name definitions) = do
  name'        <- alias name
  definitions' <- traverse renameDefinition definitions
  pure $ Definition.Module (Just name) name' definitions'
renameDefinition (Definition.Type _ name typ) = do
  name' <- alias name
  typ'  <- renameNode typ
  pure $ Definition.Type (Just name) name' typ'
renameDefinition (Definition.Function _ name arguments body) = do
  name'      <- alias name
  arguments' <- traverse alias arguments
  body'      <- adds arguments $ renameNode body
  pure $ Definition.Function (Just name) name' arguments' body'
renameDefinition (Definition.Constant _ name body) = do
  name' <- alias name
  body' <- renameNode body
  pure $ Definition.Constant (Just name) name' body'

instance Renameable Type.Type where
  renameNode = undefined
