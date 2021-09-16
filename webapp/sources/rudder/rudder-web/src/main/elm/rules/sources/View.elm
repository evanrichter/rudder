module View exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h3, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute, tabindex)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ViewRulesTable exposing (..)
import ViewRuleDetails exposing (..)
import ViewCategoryDetails exposing (..)
import ViewUtilsRules exposing (..)

view : Model -> Html Msg
view model =
  let
    ruleTreeElem : Rule -> Html Msg
    ruleTreeElem item =
      let
        (classDisabled, badgeDisabled) = if item.enabled /= True then
            (" item-disabled", span[ class "badge-disabled"][])
          else
            ("", text "")
      in
        li [class "jstree-node jstree-leaf"]
        [ i[class "jstree-icon jstree-ocl"][]
        , a[class ("jstree-anchor"++classDisabled), onClick (OpenRuleDetails item.id True)]
          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
          , span [class "treeGroupName tooltipable"]
            [ text item.name
            , badgeDisabled
            ]
          ]
        ]

    ruleTreeCategory : (Category Rule) -> Maybe (Html Msg)
    ruleTreeCategory item =
      let
        categories = getSubElems item
          |> List.sortBy .name
          |> List.filterMap ruleTreeCategory

        rules = item.elems
          |> List.filter (filterRules model)
          |> List.sortBy .name
          |> List.map ruleTreeElem

        childsList  = ul[class "jstree-children"] (List.concat [ categories, rules] )
      in
        if (String.isEmpty model.ui.ruleFilters.filter) || ((List.length rules > 0) || (List.length categories > 0)) then
          Just (
            li[class "jstree-node jstree-open"]
            [ i[class "jstree-icon jstree-ocl"][]
            , a[href "#", class "jstree-anchor", onClick (OpenCategoryDetails item.id True)]
              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
              , span [class "treeGroupCategoryName tooltipable"][text item.name]
              ]
            , childsList
            ]
          )
        else
          Nothing

    templateMain = case model.mode of
      Loading -> text "loading"
      RuleTable   ->
        let
          thClass : SortBy -> String
          thClass sortBy =
            if sortBy == model.ui.ruleFilters.sortBy then
              if model.ui.ruleFilters.sortOrder then
                "sorting_asc"
              else
                "sorting_desc"
            else
              "sorting"
        in
          div [class "main-details"]
          [ div [class "main-table"]
            [ table [ class "no-footer dataTable"]
              [ thead []
                [ tr [class "head"]
                  [ th [class (thClass Name      ) , rowspan 1, colspan 1, onClick (UpdateRuleFilters Name      )][text "Name"          ]
                  , th [class (thClass Parent    ) , rowspan 1, colspan 1, onClick (UpdateRuleFilters Parent    )][text "Category"      ]
                  , th [class (thClass Status    ) , rowspan 1, colspan 1, onClick (UpdateRuleFilters Status    )][text "Status"        ]
                  , th [class (thClass Compliance) , rowspan 1, colspan 1, onClick (UpdateRuleFilters Compliance)][text "Compliance"    ]
                  , th [class ""                   , rowspan 1, colspan 1][text "Recent changes"]
                  ]
                ]
              , tbody [] (buildRulesTable model)
              ]
            ]
          ]

      RuleForm details ->
        (editionTemplate model details)

      CategoryForm details ->
        (editionTemplateCat model details)

    modal = case model.ui.modal of
      NoModal -> text ""
      DeletionValidation rule ->
        div [ tabindex -1, class "modal fade in", style "z-index" "1050", style "display" "block" ]
        [ div [ class "modal-dialog" ] [
            div [ class "modal-content" ] [
              div [ class "modal-header ng-scope" ] [
                h3 [ class "modal-title" ] [ text "Delete Rule"]
              ]
            , div [ class "modal-body" ] [
                text ("Are you sure you want to Delete rule '"++ rule.name ++"'?")
              ]
            , div [ class "modal-footer" ] [
                button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                [ text "Cancel " ]
              , button [ class "btn btn-danger", onClick (ClosePopup (CallApi (deleteRule rule))) ]
                [ text "Delete "
                , i [ class "fa fa-times-circle" ] []
                ]
              ]
            ]
          ]
        ]
      DeactivationValidation rule ->
        let
          txtDisable = if rule.enabled then "Disable" else "Enable"
        in
          div [ tabindex -1, class "modal fade in", style "z-index" "1050", style "display" "block" ]
          [ div [ class "modal-dialog" ] [
              div [ class "modal-content" ]  [
                div [ class "modal-header ng-scope" ] [
                  h3 [ class "modal-title" ] [ text (txtDisable ++" Rule")]
                ]
              , div [ class "modal-body" ] [
                  text ("Are you sure you want to "++ String.toLower txtDisable ++" rule '"++ rule.name ++"'?")
                ]
              , div [ class "modal-footer" ] [
                  button [ class "btn btn-primary btn-outline pull-left", onClick (ClosePopup Ignore) ]
                  [ text "Cancel "
                  , i [ class "fa fa-arrow-left" ] []
                  ]
                , button [ class "btn btn-primary", onClick (ClosePopup DisableRule) ]
                  [ text (txtDisable ++ " ")
                  , i [ class "fa fa-ban" ] []
                  ]
                ]
              ]
            ]
          ]
      DeletionValidationCat category ->
        div [ tabindex -1, class "modal fade in", style "z-index" "1050", style "display" "block" ]
         [ div [ class "modal-dialog" ] [
             div [ class "modal-content" ] [
               div [ class "modal-header ng-scope" ] [
                 h3 [ class "modal-title" ] [ text "Delete category"]
               ]
             , div [ class "modal-body" ] [
                 text ("Are you sure you want to delete category '"++ category.name ++"'?")
               ]
             , div [ class "modal-footer" ] [
                 button [ class "btn btn-default", onClick (ClosePopup Ignore) ]
                 [ text "Cancel " ]
               , button [ class "btn btn-danger", onClick (ClosePopup (CallApi (deleteCategory category))) ]
                 [ text "Delete "
                 , i [ class "fa fa-times-circle" ] []
                 ]
               ]
             ]
           ]
         ]
  in
    div [class "rudder-template"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [class "sidebar-header"]
        [ div [class "header-title"]
          [ h1[]
            [ span[][text "Rules"]
            ]
          , ( if model.ui.hasWriteRights then
              div [class "header-buttons"]
              [ button [class "btn btn-default", type_ "button", onClick (GenerateId (\s -> NewCategory s      ))][text "Add Category"]
              , button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewRule (RuleId s) ))][text "Create"]
              ]
            else
              text ""
            )
          ]
        , div [class "header-filter"]
          [ div [class "input-group"]
            [ div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
              ]
            , input[type_ "text", value model.ui.ruleFilters.filter ,placeholder "Filter", class "form-control", onInput (\s -> RulesSearch s)][]
            , div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button", onClick (RulesSearch "")][span [class "fa fa-times"][]]
              ]
            ]
          ]
        ]
      , div [class "sidebar-body"]
        [ div [class "sidebar-list"]
          [ div [class "jstree jstree-default"]
            [ ul[class "jstree-container-ul jstree-children"]
              [(case ruleTreeCategory model.rulesTree of
                Just html -> html
                Nothing   -> div [class "alert alert-warning"]
                  [ i [class "fa fa-exclamation-triangle"][]
                  , text  "No rules match your filter."
                  ]
              )]
            ]
          ]
        ]
      ]
    , div [class "template-main"]
      [ templateMain ]
    , modal
    ]