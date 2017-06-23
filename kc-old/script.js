var demo = angular.module('demo', []);

demo.controller('DemoCtrl', function ($scope) {
    if (localStorage.keycloakConfig) {
        $scope.keycloakConfig = angular.fromJson(localStorage.keycloakConfig);
    }

    $scope.showConfig = false;

    $scope.init = function () {
        if (!$scope.keycloakConfig) {
            $scope.keycloakConfig = {};
        }

        if (!$scope.keycloakConfig.url) {
            $scope.configError = 'URL missing';
            $scope.showConfig = true;
            return;
        }

        if ($scope.keycloakConfig.url.charAt($scope.keycloakConfig.url.length - 1) == '/') {
            $scope.keycloakConfig.url = $scope.keycloakConfig.url.substring(0, $scope.keycloakConfig.url.length - 1);
        }

        if (!$scope.keycloakConfig.realm || !$scope.keycloakConfig.clientId || !$scope.keycloakConfig.clientSecret) {
            $scope.configError = 'Not configured';
            return;
        }

        delete $scope.configError;

        $scope.keycloak = new Keycloak($scope.keycloakConfig);
        $scope.keycloak.init(function () {
            $scope.$apply();

            $scope.keycloak.loadUserProfile(function () {
                $scope.$apply();
            }, function () {
                $scope.$apply(function () {
                    $scope.configError = 'Failed to load profile';
                })
            })

        }, function () {
            $scope.$apply(function () {
                $scope.configError = 'Auth failed';
            });
        });
    }

    $scope.save = function () {
        $scope.init();
        localStorage.keycloakConfig = angular.toJson($scope.keycloakConfig);
        $scope.showConfig = false;
    }

    $scope.init();

    $scope.login = function () {
        $scope.keycloak.login();
    }

    $scope.logout = function () {
        $scope.keycloak.logout();
    }

    $scope.reset = function () {
        delete sessionStorage.keycloakConfig;
        delete sessionStorage.oauthToken;
        delete oauth;

        $scope.keycloakConfig = null;
        $scope.keycloak = null;
    }

});
