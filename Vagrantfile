# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure('2') do |config|
  config.vm.define 'odva' do |machine|
    machine.vm.box = "ubuntu/xenial64"

    machine.vm.network "private_network", type: "dhcp"
#    machine.vm.provider :virtualbox do |v|
#      v.memory = 4096
#    end
  end

  config.vm.provision :shell, :path => "ansible/setup.sh"
end
