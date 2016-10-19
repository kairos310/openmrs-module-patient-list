/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and
 * limitations under the License.
 *
 * Copyright (C) OpenHMIS.  All Rights Reserved.
 *
 */

(function() {
	'use strict';

	var base = angular.module('app.genericEntityController');
	base.controller("PatientListController", PatientListController);
	PatientListController.$inject = ['$injector', '$scope', '$filter',
		'EntityRestFactory', 'PatientListRestfulService', 'PatientListModel',
		'$window', 'PaginationService', 'CookiesService'];

	function PatientListController($injector, $scope, $filter, EntityRestFactory,
	                               PatientListRestfulService, PatientListModel, $window,
	                               PaginationService, CookiesService) {
		var self = this;
		var entity_name_message_key = emr.message("patientlist.page");
		var REST_ENTITY_NAME = "list";

		// @Override
		self.setRequiredInitParameters = self.setRequiredInitParameters || function() {
				self.bindBaseParameters(PATIENT_LIST_MODULE_NAME, REST_ENTITY_NAME, entity_name_message_key, '');
			}
		/**
		 * Initializes and binds any required variable and/or function specific to entity.page
		 * @type {Function}
		 */
		// @Override
		self.bindExtraVariablesToScope = self.bindExtraVariablesToScope
			|| function() {
				$scope.limit = 5;
				$scope.currentPage = 1;
				$scope.pagingFrom = PaginationService.pagingFrom;
				$scope.pagingTo = PaginationService.pagingTo;
				$scope.totalNumOfResults = 0;

				$scope.getPatientListData = self.getPatientListData;
				self.getPatientList();
			}

		self.getPatientList = self.getPatientList || function() {
				PatientListRestfulService.getPatientList(self.onLoadPatientListSuccessful);
			}

		self.getPatientListData = self.getPatientListData || function(uuid, currentPage, limit) {
				$scope.patientListData = [];
				$scope.totalNumOfResults = 0;

				PatientListRestfulService.getPatientListData(uuid, currentPage, limit, self.onLoadPatientListDataSuccessful);
			}

		// callbacks
		self.onLoadPatientListSuccessful = self.onLoadPatientListSuccessful || function(data) {
				$scope.patientList = data.results;
			}

		self.onLoadPatientListDataSuccessful = self.onLoadPatientListDataSuccessful || function(data) {
				$scope.patientListData = data.results;
				$scope.totalNumOfResults = data.length;
			}

		// @Override
		self.cancel = self.cancel || function() {
				//$window.location = decodeURIComponent($scope.urlArgs['returnUrl']);
			}

		/* ENTRY POINT: Instantiate the base controller which loads the page */
		$injector.invoke(base.GenericEntityController, self, {
			$scope: $scope,
			$filter: $filter,
			EntityRestFactory: EntityRestFactory,
			PaginationService: PaginationService,
			GenericMetadataModel: PatientListModel,
			CookiesService: CookiesService
		});
	}
})();
